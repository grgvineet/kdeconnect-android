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

package org.kde.kdeconnect.Backends.LoopbackBackend;

import android.content.Context;

import org.kde.kdeconnect.Backends.LanBackend.LanPairingHandler;
import org.kde.kdeconnect.Backends.PairingHandler;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.NetworkPackage;

// using same pairing handler as of lan pairing handler
public class LoopbackPairingHandler extends LanPairingHandler {

    public LoopbackPairingHandler(Context context, Device device) {
        super(context, device);
    }
}
