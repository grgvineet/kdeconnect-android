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

import android.util.Log;

import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.IoSession;
import org.json.JSONObject;
import org.kde.kdeconnect.Backends.BaseLink;
import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.NotYetConnectedException;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class LanLink extends BaseLink {

    private IoSession session = null;

    public void disconnect() {
        if (session == null) return;
        //Log.i("LanLink", "Disconnect: "+session.getRemoteAddress().toString());
        session.close(true);
    }

    public LanLink(IoSession session, String deviceId, BaseLinkProvider linkProvider) {
        super(deviceId, linkProvider);
        this.session = session;
    }

    //Blocking, do not call from main thread
    private void sendPackageInternal(NetworkPackage np, final Device.SendPackageStatusCallback callback, PublicKey key) {
        if (session == null) {
            Log.e("sendPackage", "Not yet connected");
            callback.sendFailure(new NotYetConnectedException());
            return;
        }

        try {

            //Prepare socket for the payload
            final SSLServerSocket server;
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
                np = np.encrypt(key);
            }

            //Send body of the network package
            WriteFuture future = session.write(np.serialize());
            future.awaitUninterruptibly();
            if (!future.isWritten()) {
                Log.e("sendPackage", "!future.isWritten()");
                callback.sendFailure(future.getException());
                return;
            }

            //Send payload
            if (server != null) {
                OutputStream socket = null;
                try {
                    //Wait a maximum of 10 seconds for the other end to establish a connection with our socket, close it afterwards
                    Timer timeout = new Timer();
                    timeout.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            Log.e("sendPackage","Timeout");
                            try { server.close(); } catch (Exception e) { }
                            callback.sendFailure(new TimeoutException("Timed out waiting for other end to establish a connection to receive the payload."));
                        }
                    },10*1000);
                    socket = server.accept().getOutputStream();
                    timeout.cancel();

                    Log.i("LanLink", "Beginning to send payload");

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
                    Log.i("LanLink", "Finished sending payload");
                } catch (Exception e) {
                    Log.e("sendPackage", "Exception: "+e);
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
        sendPackageInternal(np, callback, key);
    }

    public void injectNetworkPackage(NetworkPackage np) {

        if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_ENCRYPTED)) {

            try {
                np = np.decrypt(privateKey);
            } catch(Exception e) {
                e.printStackTrace();
                Log.e("onPackageReceived","Exception reading the key needed to decrypt the package");
            }

        }

        if (np.hasPayloadTransferInfo()) {

            try {

                int tcpPort = np.getPayloadTransferInfo().getInt("port");
                InetSocketAddress address = (InetSocketAddress)session.getRemoteAddress();

                TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
//                    return null;
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }

                }
                };

                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trustAllCerts, new java.security.SecureRandom());

                SSLSocketFactory socketFactory = (SSLSocketFactory) context.getSocketFactory();
                SSLSocket socket = (SSLSocket) socketFactory.createSocket(address.getAddress(),tcpPort);
                System.out.println("here");
                System.out.println("" + socket.getLocalAddress() + " " + socket.getLocalPort() );
                System.out.println(""+ socket.getInetAddress() + " " + socket.getPort() );

                socket.addHandshakeCompletedListener(new HandshakeCompletedListener(){

                    @Override
                    public void handshakeCompleted(HandshakeCompletedEvent hce) {
                        System.out.println("Handshake completed");
                    }

                });

//                Socket socket = new Socket();wi
//                socket.connect(new InetSocketAddress(address.getAddress(), tcpPort));
                np.setPayload(socket.getInputStream(), np.getPayloadSize());
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("LanLink", "Exception connecting to payload remote socket");
            }

        }

        packageReceived(np);
    }


    static SSLServerSocket openTcpSocketOnFreePort() throws Exception {
        boolean success = false;
        int tcpPort = 1739;
        String ksName = "/sdcard/kdeconnect.bks";
        char[] ksPass = "kdeconnect".toCharArray();


        SSLServerSocket candidateServer = null;
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(new FileInputStream(ksName), ksPass);
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, ksPass);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        SSLServerSocketFactory sslServerSocketFactory = (SSLServerSocketFactory) sslContext.getServerSocketFactory();

        while(!success) {
            try {
                candidateServer = (SSLServerSocket) sslServerSocketFactory.createServerSocket();
                candidateServer.bind(new InetSocketAddress(tcpPort));
                success = true;
                Log.i("LanLink", "Using port "+tcpPort);
            } catch(IOException e) {
                //Log.e("LanLink", "Exception openning serversocket: "+e);
                tcpPort++;
                if (tcpPort >= 1764) {
                    Log.e("LanLink", "No more ports available");
                    throw e;
                }
            }
        }
        return candidateServer;
    }

}
