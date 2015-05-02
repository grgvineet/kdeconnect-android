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

package org.kde.kdeconnect.Backends;

import android.content.Context;

import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

import java.util.ArrayList;

public abstract  class PairingHandler {

    protected final Context context;
    protected final Device device;
    protected int notificationId;
    protected ArrayList<PairingCallback> pairingCallback = new ArrayList<PairingHandler.PairingCallback>();

    public enum PairStatus {
        NotPaired,
        Requested,
        RequestedByPeer,
        Paired
    }

    protected PairingHandler(Context context, Device device) {
        this.context = context;
        this.device = device;
    }

    public void addPairingCallback(PairingHandler.PairingCallback callback) {
        pairingCallback.add(callback);
        if (device.getPairStatus() == PairStatus.RequestedByPeer) {
            callback.incomingRequest();
        }
    }
    public void removePairingCallback(PairingHandler.PairingCallback callback) {
        pairingCallback.remove(callback);
    }

    public int getNotificationId() {
        return notificationId;
    }

    public interface PairingCallback {
        abstract void incomingRequest();
        abstract void pairingSuccessful();
        abstract void pairingFailed(String error);
        abstract void unpaired();
    }

    public abstract void handlePairingPackage(NetworkPackage np);
    public abstract void requestPairing();
    public abstract void unpair();
    public abstract void acceptPairing();
    public abstract void rejectPairing();
}
