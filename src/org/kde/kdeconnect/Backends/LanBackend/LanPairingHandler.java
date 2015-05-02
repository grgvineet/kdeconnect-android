/*
 * Copyright 2015 Vineet Garg <grgvineet@gmail.com>
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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.support.v4.app.NotificationCompat;
import android.util.Base64;
import android.util.Log;

import org.kde.kdeconnect.Backends.PairingHandler;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;
import org.kde.kdeconnect.UserInterface.PairActivity;
import org.kde.kdeconnect_tp.R;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class LanPairingHandler extends PairingHandler {

    private Timer pairingTimer;
    private PublicKey publicKey;

    public LanPairingHandler(Context context, Device device){
        super(context, device);
    }

    public void handlePairingPackage(NetworkPackage np) {
        Log.i("KDE/Device","Pair package");

        boolean wantsPair = np.getBoolean("pair");

        if (wantsPair == device.isPaired()) {
            if (device.getPairStatus() == PairStatus.Requested) {
                //Log.e("Device","Unpairing (pair rejected)");
                device.setPairStatus(PairStatus.NotPaired);
                if (pairingTimer != null) pairingTimer.cancel();
                for (PairingCallback cb : pairingCallback) {
                    cb.pairingFailed(context.getString(R.string.error_canceled_by_other_peer));
                }
            }
            return;
        }

        if (wantsPair) {

            //Retrieve their public key
            try {
                String publicKeyContent = np.getString("publicKey").replace("-----BEGIN PUBLIC KEY-----\n","").replace("-----END PUBLIC KEY-----\n","");
                byte[] publicKeyBytes = Base64.decode(publicKeyContent, 0);
                publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            } catch(Exception e) {
                e.printStackTrace();
                Log.e("KDE/Device","Pairing exception: Received incorrect key");
                for (PairingCallback cb : pairingCallback) {
                    cb.pairingFailed(context.getString(R.string.error_invalid_key));
                }
                return;
            }

            if (device.getPairStatus() == PairStatus.Requested)  { //We started pairing

                Log.i("KDE/Pairing","Pair answer");

                if (pairingTimer != null) pairingTimer.cancel();

                pairingDone();

            } else {

                Log.i("KDE/Pairing","Pair request");

                Intent intent = new Intent(context, PairActivity.class);
                intent.putExtra("deviceId", device.getDeviceId());
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT);

                Resources res = context.getResources();

                Notification noti = new NotificationCompat.Builder(context)
                        .setContentTitle(res.getString(R.string.pairing_request_from, device.getName()))
                        .setContentText(res.getString(R.string.tap_to_answer))
                        .setContentIntent(pendingIntent)
                        .setTicker(res.getString(R.string.pair_requested))
                        .setSmallIcon(android.R.drawable.ic_menu_help)
                        .setAutoCancel(true)
                        .setDefaults(Notification.DEFAULT_ALL)
                        .build();


                final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                notificationId = (int)System.currentTimeMillis();
                notificationManager.notify(notificationId, noti);

                if (pairingTimer != null) pairingTimer.cancel();
                pairingTimer = new Timer();

                pairingTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        Log.e("KDE/Device","Unpairing (timeout B)");
                        device.setPairStatus(PairStatus.NotPaired);
                        notificationManager.cancel(notificationId);
                    }
                }, 25*1000); //Time to show notification, waiting for user to accept (peer will timeout in 30 seconds)
                device.setPairStatus(PairStatus.RequestedByPeer);
                for (PairingCallback cb : pairingCallback) cb.incomingRequest();

            }
        } else {
            Log.i("KDE/Pairing","Unpair request");

            if (device.getPairStatus() == PairStatus.Requested) {
                pairingTimer.cancel();
                for (PairingCallback cb : pairingCallback) {
                    cb.pairingFailed(context.getString(R.string.error_canceled_by_other_peer));
                }
            } else if (device.getPairStatus() == PairStatus.Paired) {
                SharedPreferences preferences = context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE);
                preferences.edit().remove(device.getDeviceId()).apply();
                device.reloadPluginsFromSettings();
            }

            device.setPairStatus(PairStatus.NotPaired);
            for (PairingCallback cb : pairingCallback) cb.unpaired();

        }
    }

    public void requestPairing() {

        Resources res = context.getResources();

        if (device.getPairStatus() == PairStatus.Paired) {
            for (PairingCallback cb : pairingCallback) {
                cb.pairingFailed(res.getString(R.string.error_already_paired));
            }
            return;
        }
        if (device.getPairStatus() == PairStatus.Requested) {
            for (PairingCallback cb : pairingCallback) {
                cb.pairingFailed(res.getString(R.string.error_already_requested));
            }
            return;
        }
        if (!device.isReachable()) {
            for (PairingCallback cb : pairingCallback) {
                cb.pairingFailed(res.getString(R.string.error_not_reachable));
            }
            return;
        }

        //Send our own public key
        NetworkPackage np = NetworkPackage.createPublicKeyPackage(context);
        device.sendPackage(np, new Device.SendPackageStatusCallback() {
            @Override
            public void onSuccess() {
                if (pairingTimer != null) pairingTimer.cancel();
                pairingTimer = new Timer();
                pairingTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        for (PairingCallback cb : pairingCallback) {
                            cb.pairingFailed(context.getString(R.string.error_timed_out));
                        }
                        Log.e("KDE/Device", "Unpairing (timeout A)");
                        device.setPairStatus(PairStatus.NotPaired);
                    }
                }, 30 * 1000); //Time to wait for the other to accept
                device.setPairStatus(PairStatus.Requested);
            }

            @Override
            public void onFailure(Throwable e) {
                for (PairingCallback cb : pairingCallback) {
                    cb.pairingFailed(context.getString(R.string.error_could_not_send_package));
                }
                Log.e("KDE/Device", "Unpairing (sendFailed A)");
                device.setPairStatus(PairStatus.NotPaired);
            }

        });

    }

    public void unpair() {

        //Log.e("Device","Unpairing (unpair)");
        device.setPairStatus(PairStatus.NotPaired);

        SharedPreferences preferences = context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE);
        preferences.edit().remove(device.getDeviceId()).apply();

        NetworkPackage np = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_PAIR);
        np.set("pair", false);
        device.sendPackage(np);

        for (PairingCallback cb : pairingCallback) cb.unpaired();

        device.reloadPluginsFromSettings();

    }

    public void pairingDone() {

        //Log.e("Device", "Storing as trusted, deviceId: "+deviceId);

        if (pairingTimer != null) pairingTimer.cancel();

        device.setPairStatus(PairStatus.Paired);

        //Store as trusted device
        SharedPreferences preferences = context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE);
        preferences.edit().putBoolean(device.getDeviceId(),true).apply();

        //Store device information needed to create a Device object in a future
        SharedPreferences.Editor editor = context.getSharedPreferences(device.getDeviceId(), Context.MODE_PRIVATE).edit();
        editor.putString("deviceName", device.getName());
        editor.putString("type", device.getType());
        String encodedPublicKey = Base64.encodeToString(publicKey.getEncoded(), 0);
        editor.putString("publicKey", encodedPublicKey);
        editor.apply();

        device.reloadPluginsFromSettings();
        device.initialiseLinks();

        for (PairingCallback cb : pairingCallback) {
            cb.pairingSuccessful();
        }

    }

    public void acceptPairing() {

        Log.i("KDE/Device","Accepted pair request started by the other device");

        //Send our own public key
        NetworkPackage np = NetworkPackage.createPublicKeyPackage(context);
        device.sendPackage(np, new Device.SendPackageStatusCallback() {
            @Override
            protected void onSuccess() {
                pairingDone();
            }

            @Override
            protected void onFailure(Throwable e) {
                Log.e("Device", "Unpairing (sendFailed B)");
                device.setPairStatus(PairStatus.NotPaired);
                for (PairingCallback cb : pairingCallback) {
                    cb.pairingFailed(context.getString(R.string.error_not_reachable));
                }
            }
        });

    }

    public void rejectPairing() {

        Log.i("KDE/Device","Rejected pair request started by the other device");

        //Log.e("Device","Unpairing (rejectPairing)");
        device.setPairStatus(PairStatus.NotPaired);

        NetworkPackage np = new NetworkPackage(NetworkPackage.PACKAGE_TYPE_PAIR);
        np.set("pair", false);
        device.sendPackage(np);

        for (PairingCallback cb : pairingCallback) {
            cb.pairingFailed(context.getString(R.string.error_canceled_by_user));
        }

    }
}
