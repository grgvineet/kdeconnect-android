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
import android.util.Log;

import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.json.JSONObject;
import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.Helpers.SecurityHelpers.RsaHelper;
import org.kde.kdeconnect.NetworkPackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.NotYetConnectedException;
import java.security.PublicKey;

public class LanLink extends BaseLink {

    private IoSession session = null;
    private boolean onSsl = false;

    public void disconnect() {
        if (session == null) {
            Log.e("KDE/LanLink", "Not yet connected");
            return;
        }
        session.close(true);
    }

    public LanLink(Context context,IoSession session, String deviceId, BaseLinkProvider linkProvider) {
        super(context,deviceId, linkProvider);
        this.session = session;
    }

    public void setOnSsl(boolean value){
        this.onSsl = value;
    }

    //Blocking, do not call from main thread
    private void sendPackageInternal(NetworkPackage np, final Device.SendPackageStatusCallback callback, PublicKey key) {
        if (session == null) {
            Log.e("KDE/sendPackage", "Not yet connected");
            callback.sendFailure(new NotYetConnectedException());
            return;
        }

        try {

            //Prepare socket for the payload
            // TODO : Change this to incorporate payload sending via ssl
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
            if (key != null) {
                np = RsaHelper.encrypt(np, key);
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
            // TODO : Change this to incorporate payload sending via ssl
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
        sendPackageInternal(np, callback, null);

    }

    //Blocking, do not call from main thread
    @Override
    public void sendPackageEncrypted(NetworkPackage np, Device.SendPackageStatusCallback callback, PublicKey key) {
        if (onSsl){
            Log.e("KDE/LanLink", "Link is on ssl, not encrypting data");
            sendPackageInternal(np, callback, null); // No need to encrypt if on ssl
        }else {
            sendPackageInternal(np, callback, key);
        }
    }

    public void injectNetworkPackage(NetworkPackage np) {

        if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_ENCRYPTED)) {

            try {
                np = RsaHelper.decrypt(np, privateKey);
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

}
