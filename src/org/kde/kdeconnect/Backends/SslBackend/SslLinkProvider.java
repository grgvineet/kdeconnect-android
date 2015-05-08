/*
 * Copyright 2015 Vineet Garg <grgvineetka@gmail.com>
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

package org.kde.kdeconnect.Backends.SslBackend;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.v4.util.LongSparseArray;
import android.util.Base64;
import android.util.Log;

import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.textline.LineDelimiter;
import org.apache.mina.filter.codec.textline.TextLineCodecFactory;
import org.apache.mina.filter.ssl.SslFilter;
import org.apache.mina.transport.socket.nio.NioDatagramAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.kde.kdeconnect.Backends.BaseLinkProvider;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;
import org.kde.kdeconnect.UserInterface.CustomDevicesActivity;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SslLinkProvider extends BaseLinkProvider{
    public static final String KEY_CUSTOM_DEVLIST_PREFERENCE  = "device_list_preference";
    private final static int port = 1714;

    private final Context context;
    private final HashMap<String, SslLink> visibleComputers = new HashMap<String, SslLink>();
    private final LongSparseArray<SslLink> nioSessions = new LongSparseArray<SslLink>();
    private final LongSparseArray<NioSocketConnector> nioConnectors = new LongSparseArray<NioSocketConnector>();

    private NioSocketAcceptor tcpAcceptor = null;
    private NioDatagramAcceptor udpAcceptor = null;

    private final IoHandler tcpHandler = new IoHandlerAdapter() {

        @Override
        public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
            super.exceptionCaught(session, cause);
            cause.printStackTrace();
        }


        @Override
        public void sessionClosed(IoSession session) throws Exception {
            Log.e("KDE/SslLinkProvider","Closing session " + session.getCreationTime());
            try {
                long id = session.getId();
                final SslLink brokenLink = nioSessions.get(id);
                NioSocketConnector connector = nioConnectors.get(id);
                if (connector != null) {
                    connector.dispose();
                    nioConnectors.remove(id);
                }
                if (brokenLink != null) {
                    nioSessions.remove(id);
                    //Log.i("KDE/SslLinkProvider", "nioSessions.size(): " + nioSessions.size() + " (-)");
                    try {
                        brokenLink.disconnect();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.e("KDE/SslLinkProvider", "Exception. Already disconnected?");
                    }
                    //Log.i("KDE/SslLinkProvider", "Disconnected!");
                    String deviceId = brokenLink.getDeviceId();
                    if (visibleComputers.get(deviceId) == brokenLink) {
                        visibleComputers.remove(deviceId);
                    }
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            //Wait a bit before emiting connectionLost, in case the same device re-appears
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException e) {
                            }
                            connectionLost(brokenLink);

                        }
                    }).start();

                }
            } catch (Exception e) { //If we don't catch it here, Mina will swallow it :/
                e.printStackTrace();
                Log.e("KDE/SslLinkProvider", "sessionClosed exception");
            }
        }

        @Override
        public void messageReceived(IoSession session, Object message) throws Exception {
            super.messageReceived(session, message);

            //Log.e("SslLinkProvider","Incoming package, address: "+session.getRemoteAddress()).toString());
            //Log.e("SslLinkProvider","Received:"+message);

            String theMessage = (String) message;
            if (theMessage.isEmpty()) {
                Log.e("KDE/SslLinkProvider","Empty package received");
                return;
            }

            NetworkPackage np = NetworkPackage.unserialize(theMessage);

            if (np.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {

                String myId = NetworkPackage.createIdentityPackage(context).getString("deviceId");
                if (np.getString("deviceId").equals(myId)) {
                    return;
                }

                //Log.i("KDE/SslLinkProvider", "Identity package received from " + np.getString("deviceName"));

                SslLink link = new SslLink(context, session, np.getString("deviceId"), SslLinkProvider.this);
                nioSessions.put(session.getId(),link);
                //Log.e("KDE/SslLinkProvider","nioSessions.size(): " + nioSessions.size());
                addLink(np, link);
            } else {
                SslLink prevLink = nioSessions.get(session.getId());
                if (prevLink == null) {
                    Log.e("KDE/SslLinkProvider","Expecting an identity package (A)");
                } else {
                    Log.e("KDE/SslLinkProvider",np.serialize());
                    prevLink.injectNetworkPackage(np);
                }
            }

        }
    };

    private final IoHandler udpHandler = new IoHandlerAdapter() {
        @Override
        public void messageReceived(IoSession udpSession, Object message) throws Exception {
            super.messageReceived(udpSession, message);

            Log.e("KDE/SslLinkProvider", "Udp message received (" + message.getClass() + ") " + message.toString());

            try {
                //We should receive a string thanks to the TextLineCodecFactory filter
                String theMessage = (String) message;
                final NetworkPackage identityPackage = NetworkPackage.unserialize(theMessage);

                if (!identityPackage.getType().equals(NetworkPackage.PACKAGE_TYPE_IDENTITY)) {
                    Log.e("KDE/SslLinkProvider", "Expecting an identity package (B)");
                    return;
                } else {
                    String myId = NetworkPackage.createIdentityPackage(context).getString("deviceId");
                    if (identityPackage.getString("deviceId").equals(myId)) {
                        return;
                    }
                }

                Log.i("KDE/SslLinkProvider", "Identity package received, creating link");

                final InetSocketAddress address = (InetSocketAddress) udpSession.getRemoteAddress();

                final NioSocketConnector connector = new NioSocketConnector();
                connector.setHandler(tcpHandler);
                connector.getSessionConfig().setKeepAlive(true);
                //TextLineCodecFactory will buffer incoming data and emit a message very time it finds a \n
                TextLineCodecFactory textLineFactory = new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX);
                textLineFactory.setDecoderMaxLineLength(512*1024); //Allow to receive up to 512kb of data
                connector.getFilterChain().addLast("sslFilter", getSslFilter(true));
                connector.getFilterChain().addLast("codec", new ProtocolCodecFilter(textLineFactory));

                int tcpPort = identityPackage.getInt("tcpPort", port);
                final ConnectFuture future = connector.connect(new InetSocketAddress(address.getAddress(), tcpPort));
                future.addListener(new IoFutureListener<IoFuture>() {


                    @Override
                    public void operationComplete(IoFuture ioFuture) {
                        try {
                            future.removeListener(this);
                            final IoSession session = ioFuture.getSession();

                            Log.i("KDE/SslLinkProvider", "Connection successful: " + session.isConnected());

                            final SslLink link = new SslLink(context, session, identityPackage.getString("deviceId"), SslLinkProvider.this);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    NetworkPackage np2 = NetworkPackage.createIdentityPackage(context);
                                    link.sendPackage(np2,new Device.SendPackageStatusCallback() {
                                        @Override
                                        protected void onSuccess() {
                                            nioSessions.put(session.getId(), link);
                                            nioConnectors.put(session.getId(), connector);
                                            Log.e("KDE/SslLinkProvider","nioSessions.size(): " + nioSessions.size());
                                            addLink(identityPackage, link);
                                        }

                                        @Override
                                        protected void onFailure(Throwable e) {
                                            e.printStackTrace();
                                            Log.e("KDE/SslLinkProvider", "Connection failed: could not send identity package back");
                                        }

                                    });

                                }
                            }).start();
                        } catch (Exception e) { //If we don't catch it here, Mina will swallow it :/
                            e.printStackTrace();
                            Log.e("KDE/SslLinkProvider", "sessionClosed exception");
                        }
                    }
                });

            } catch (Exception e) {
                Log.e("KDE/SslLinkProvider","Exception receiving udp package!!");
                e.printStackTrace();
            }

        }
    };

    private void addLink(NetworkPackage identityPackage, SslLink link) {
        String deviceId = identityPackage.getString("deviceId");
        Log.i("KDE/SslLinkProvider","addLink to "+deviceId);
        SslLink oldLink = visibleComputers.get(deviceId);
        if (oldLink == link) {
            Log.e("KDE/SslLinkProvider", "oldLink == link. This should not happen!");
            return;
        }
        visibleComputers.put(deviceId, link);
        connectionAccepted(identityPackage, link);
        if (oldLink != null) {
            Log.i("KDE/SslLinkProvider","Removing old connection to same device");
            oldLink.disconnect();
            connectionLost(oldLink);
        }
    }

    public SslFilter getSslFilter(boolean isClient) {
        PrivateKey privateKey = null;

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

        SharedPreferences globalSettings = PreferenceManager.getDefaultSharedPreferences(context);
        try {
            byte[] privateKeyBytes = Base64.decode(globalSettings.getString("privateKey", ""), 0);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        }catch (Exception e){
            e.printStackTrace();
            Log.e("KDE/Device","Exception");
        }

        try {

            byte[] certificateBytes = Base64.decode(globalSettings.getString("certificate", ""), 0);

            X509CertificateHolder certificateHolder = new X509CertificateHolder(certificateBytes);
            X509Certificate certificate = new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider()).getCertificate(certificateHolder);

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setKeyEntry("my_key_and_certificate",privateKey,"".toCharArray(),new Certificate[]{certificate});

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, "".toCharArray());

            SSLContext tlsContext = SSLContext.getInstance("TLS");
            tlsContext.init(keyManagerFactory.getKeyManagers(), trustAllCerts, new SecureRandom());
            SslFilter filter = new SslFilter(tlsContext,true);

            if (isClient){
                filter.setUseClientMode(true);
            }else {
                filter.setUseClientMode(false);
            }

            filter.setWantClientAuth(true);

            return filter;
        }catch (Exception e){
            e.printStackTrace();
        }
        return null;
    }

    public SslLinkProvider(Context context) {

        this.context = context;

        //This handles the case when I'm the new device in the network and somebody answers my introduction package
        tcpAcceptor = new NioSocketAcceptor();
        tcpAcceptor.setHandler(tcpHandler);
        tcpAcceptor.getSessionConfig().setKeepAlive(true);
        tcpAcceptor.getSessionConfig().setReuseAddress(true);
        //TextLineCodecFactory will buffer incoming data and emit a message very time it finds a \n
        TextLineCodecFactory textLineFactory = new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX);
        textLineFactory.setDecoderMaxLineLength(512*1024); //Allow to receive up to 512kb of data
        tcpAcceptor.getFilterChain().addFirst("sslFilter", getSslFilter(false));
        tcpAcceptor.getFilterChain().addLast("codec", new ProtocolCodecFilter(textLineFactory));


        udpAcceptor = new NioDatagramAcceptor();
        udpAcceptor.getSessionConfig().setReuseAddress(true); //Share port if existing
        //TextLineCodecFactory will buffer incoming data and emit a message very time it finds a \n
        //This one will have the default MaxLineLength of 1KB
        udpAcceptor.getFilterChain().addLast("codec",
                new ProtocolCodecFilter(
                        new TextLineCodecFactory(Charset.defaultCharset(), LineDelimiter.UNIX, LineDelimiter.UNIX)
                )
        );

    }

    @Override
    public void onStart() {

        //This handles the case when I'm the existing device in the network and receive a "hello" UDP package

        Set<SocketAddress> addresses = udpAcceptor.getLocalAddresses();
        for (SocketAddress address : addresses) {
            Log.i("KDE/SslLinkProvider", "UDP unbind old address");
            udpAcceptor.unbind(address);
        }

        //Log.i("KDE/SslLinkProvider", "UDP Bind.");
        udpAcceptor.setHandler(udpHandler);

        try {
            udpAcceptor.bind(new InetSocketAddress(port));
        } catch(Exception e) {
            Log.e("KDE/SslLinkProvider", "Error: Could not bind udp socket");
            e.printStackTrace();
        }

        boolean success = false;
        int tcpPort = port;
        while(!success) {
            try {
                tcpAcceptor.bind(new InetSocketAddress(tcpPort));
                success = true;
            } catch(Exception e) {
                tcpPort++;
            }
        }

        Log.i("KDE/SslLinkProvider","Using tcpPort "+tcpPort);

        //I'm on a new network, let's be polite and introduce myself
        final int finalTcpPort = tcpPort;
        new Thread(new Runnable() {
            @Override
            public void run() {

                String deviceListPrefs = PreferenceManager.getDefaultSharedPreferences(context).getString(
                        KEY_CUSTOM_DEVLIST_PREFERENCE, "");
                ArrayList<String> iplist = new ArrayList<String>();
                if (!deviceListPrefs.isEmpty()) {
                    iplist = CustomDevicesActivity.deserializeIpList(deviceListPrefs);
                }
                iplist.add("255.255.255.255"); //Default: broadcast.

                NetworkPackage identity = NetworkPackage.createIdentityPackage(context);
                identity.set("tcpPort", finalTcpPort);
                DatagramSocket socket = null;
                byte[] bytes = null;
                try {
                    socket = new DatagramSocket();
                    socket.setReuseAddress(true);
                    socket.setBroadcast(true);
                    bytes = identity.serialize().getBytes("UTF-8");
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.e("KDE/SslLinkProvider","Failed to create DatagramSocket");
                }

                if (bytes != null) {
                    //Log.e("KDE/SslLinkProvider","Sending packet to "+iplist.size()+" ips");
                    for (String ipstr : iplist) {
                        try {
                            InetAddress client = InetAddress.getByName(ipstr);
                            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, client, port);
                            socket.send(packet);
                            //Log.i("KDE/SslLinkProvider","Udp identity package sent to address "+packet.getAddress());
                        } catch (Exception e) {
                            e.printStackTrace();
                            Log.e("KDE/SslLinkProvider", "Sending udp identity package failed. Invalid address? (" + ipstr + ")");
                        }
                    }
                }

                socket.close();

            }
        }).start();
    }

    @Override
    public void onNetworkChange() {
        //Log.e("KDE/SslLinkProvider","onNetworkChange");

        //FilesHelper.LogOpenFileCount();

        //Keep existing connections open while unbinding the socket
        tcpAcceptor.setCloseOnDeactivation(false);
        onStop();
        tcpAcceptor.setCloseOnDeactivation(true);

        //FilesHelper.LogOpenFileCount();

        onStart();

        //FilesHelper.LogOpenFileCount();
    }

    @Override
    public void onStop() {
        udpAcceptor.unbind();
        tcpAcceptor.unbind();
    }

    @Override
    public String getName() {
        return "SslLinkProvider";
    }

    private boolean isDeviceKnown(String deviceId) {
        SharedPreferences preferences = context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE);

        return preferences.getBoolean(deviceId, false);
    }

    private String getCertificate(String deviceId) {

        return null;
    }


}
