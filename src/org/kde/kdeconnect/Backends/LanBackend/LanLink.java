/*
 * Copyright 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
*/

package org.kde.kdeconnect.Backends.LanBackend;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.NotYetConnectedException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeoutException;

import javax.crypto.Cipher;

public class LanLink extends BaseLink {

    private IoSession session = null;
    PrivateKey privateKey; // my private key
    PublicKey publicKey; // public key of other device

    public void disconnect() {
        if (session == null) {
            Log.e("KDE/LanLink", "Not yet connected");
            return;
        }
        session.close(true);
    }

    public LanLink(Context context,IoSession session, String deviceId, BaseLinkProvider linkProvider) {
        super(context, deviceId, linkProvider);
        this.session = session;
    }

    @Override
    public void initialiseLink() {
        // refactor : setting private key
        try {
            SharedPreferences globalSettings = PreferenceManager.getDefaultSharedPreferences(super.getContext());
            byte[] privateKeyBytes = Base64.decode(globalSettings.getString("privateKey", ""), 0);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("KDE/Device", "Exception reading our own private key"); //Should not happen
        }

        // refactor : setting public key. excetpion if device not paired
        try {
            SharedPreferences settings = super.getContext().getSharedPreferences(super.getDeviceId(), Context.MODE_PRIVATE);
            byte[] publicKeyBytes = Base64.decode(settings.getString("publicKey", ""), 0);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("KDE/Device","Exception");
        }
    }

    //Blocking, do not call from main thread
    private void sendPackageInternal(NetworkPackage np, final Device.SendPackageStatusCallback callback, boolean toEncrypt) {
        if (session == null) {
            Log.e("KDE/sendPackage", "Not yet connected");
            callback.sendFailure(new NotYetConnectedException());
            return;
        }

        try {

            //Prepare socket for the payload
            final ServerSocket server;
            if (np.hasPayload()) {
                server = openTcpSocketOnFreePort();
                JSONObject payloadTransferInfo = new JSONObject();
                payloadTransferInfo.put("port", server.getLocalPort());
                np.setPayloadTransferInfo(payloadTransferInfo);
            } else {
                server = null;
            }

            //Encrypt if key provided
            if (toEncrypt) {
                np = encrypt(np);
            }

            //Send body of the network package
            WriteFuture future = session.write(np.serialize());
            future.awaitUninterruptibly();
            if (!future.isWritten()) {
                Log.e("KDE/sendPackage", "!future.isWritten()");
                callback.sendFailure(future.getException());
                return;
            }

            //Send payload
            if (server != null) {
                OutputStream socket = null;
                try {
                    //Wait a maximum of 10 seconds for the other end to establish a connection with our socket, close it afterwards
                    server.setSoTimeout(10*1000);
                    socket = server.accept().getOutputStream();

                    Log.i("KDE/LanLink", "Beginning to send payload");

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long progress = 0;
                    InputStream stream = np.getPayload();
                    while ((bytesRead = stream.read(buffer)) != -1) {
                        //Log.e("ok",""+bytesRead);
                        progress += bytesRead;
                        socket.write(buffer, 0, bytesRead);
                        if (np.getPayloadSize() > 0) {
                            callback.sendProgress((int)(progress / np.getPayloadSize()));
                        }
                    }
                    socket.flush();
                    stream.close();
                    Log.i("KDE/LanLink", "Finished sending payload ("+progress+" bytes written)");
                } catch (Exception e) {
                    Log.e("KDE/sendPackage", "Exception: "+e);
                    callback.sendFailure(e);
                    return;
                } finally {
                    if (socket != null) {
                        try { socket.close(); } catch (Exception e) { }
                    }
                    try { server.close(); } catch (Exception e) { }
                }
            }

            callback.sendSuccess();

        } catch (Exception e) {
            if (callback != null) {
                callback.sendFailure(e);
            }
        }
    }


    //Blocking, do not call from main thread
    @Override
    public void sendPackage(NetworkPackage np,Device.SendPackageStatusCallback callback) {
        sendPackageInternal(np, callback, false);

    }

    //Blocking, do not call from main thread
    @Override
    public void sendPackageEncrypted(NetworkPackage np, Device.SendPackageStatusCallback callback) {
        sendPackageInternal(np, callback, true);
    }

    public void injectNetworkPackage(NetworkPackage np) {

        if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_ENCRYPTED)) {

            try {
                np = decrypt(np, privateKey);
            } catch(Exception e) {
                e.printStackTrace();
                Log.e("KDE/onPackageReceived","Exception reading the key needed to decrypt the package");
            }

        }

        if (np.hasPayloadTransferInfo()) {

            Socket socket = null;
            try {
                socket = new Socket();
                int tcpPort = np.getPayloadTransferInfo().getInt("port");
                InetSocketAddress address = (InetSocketAddress)session.getRemoteAddress();
                socket.connect(new InetSocketAddress(address.getAddress(), tcpPort));
                np.setPayload(socket.getInputStream(), np.getPayloadSize());
            } catch (Exception e) {
                try { socket.close(); } catch(Exception ignored) { }
                e.printStackTrace();
                Log.e("KDE/LanLink", "Exception connecting to payload remote socket");
            }

        }

        packageReceived(np);
    }


    static ServerSocket openTcpSocketOnFreePort() throws IOException {
        boolean success = false;
        int tcpPort = 1739;
        ServerSocket candidateServer = null;
        while(!success) {
            try {
                candidateServer = new ServerSocket();
                candidateServer.bind(new InetSocketAddress(tcpPort));
                success = true;
                Log.i("KDE/LanLink", "Using port "+tcpPort);
            } catch(IOException e) {
                //Log.e("LanLink", "Exception openning serversocket: "+e);
                tcpPort++;
                if (tcpPort >= 1764) {
                    Log.e("KDE/LanLink", "No more ports available");
                    throw e;
                }
            }
        }
        return candidateServer;
    }

    public NetworkPackage encrypt(NetworkPackage np) throws GeneralSecurityException {

        String serialized = np.serialize();

        int chunkSize = 128;

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1PADDING");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        JSONArray chunks = new JSONArray();
        while (serialized.length() > 0) {
            if (serialized.length() < chunkSize) {
                chunkSize = serialized.length();
            }
            String chunk = serialized.substring(0, chunkSize);
            serialized = serialized.substring(chunkSize);
            byte[] chunkBytes = chunk.getBytes(Charset.defaultCharset());
            byte[] encryptedChunk;
            encryptedChunk = cipher.doFinal(chunkBytes);
            chunks.put(Base64.encodeToString(encryptedChunk, Base64.NO_WRAP));
        }

        //Log.i("NetworkPackage", "Encrypted " + chunks.length()+" chunks");

        NetworkPackage encrypted = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_ENCRYPTED);
        encrypted.set("data", chunks);
        encrypted.setPayload(np.getPayload(), np.getPayloadSize());
        return encrypted;

    }

    public NetworkPackage decrypt(NetworkPackage np, PrivateKey privateKey)  throws GeneralSecurityException, JSONException {

        JSONArray chunks = np.getJSONArray("data");

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1PADDING");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        String decryptedJson = "";
        for (int i = 0; i < chunks.length(); i++) {
            byte[] encryptedChunk = Base64.decode(chunks.getString(i), Base64.NO_WRAP);
            String decryptedChunk = new String(cipher.doFinal(encryptedChunk));
            decryptedJson += decryptedChunk;
        }

        NetworkPackage decrypted = np.unserialize(decryptedJson);
        decrypted.setPayload(np.getPayload(), np.getPayloadSize());
        return decrypted;
    }

}
