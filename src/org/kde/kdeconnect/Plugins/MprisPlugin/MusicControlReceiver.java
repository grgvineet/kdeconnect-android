/*
 * Copyright 2014 Da-Jin Chu <dajinchu@gmail.com>
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
package org.kde.kdeconnect.Plugins.MprisPlugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import org.kde.kdeconnect.BackgroundService;
import org.kde.kdeconnect.Device;

public class MusicControlReceiver extends BroadcastReceiver {

    Context context;
    String deviceId;
    String player;


    @Override
    public void onReceive(Context context, Intent intent) {
        this.context=context;
        deviceId = intent.getStringExtra("deviceId");
        player = intent.getStringExtra("player");

        //If onReceive is called because of RemoteControlClient...
        if(intent.getAction()==Intent.ACTION_MEDIA_BUTTON) {
            //The event will fire twice, up and down.
            // we only want to handle the down event though.
            KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (key.getAction()==KeyEvent.ACTION_DOWN) {
                switch (key.getKeyCode()) {
                    case KeyEvent.KEYCODE_HEADSETHOOK:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        play();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        play();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        pause();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_STOP:
                        pause();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        next();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        prev();
                        break;
                    default:
                        return;
                }
            }
        }else {
            //Not from RemoteControlClient, from NotificationPanel
            String action = intent.getStringExtra("action");
            if (action.equals("play")) {
                play();
            } else if (action.equals("next")) {
                next();
            } else if (action.equals("prev")) {
                prev();
            }
        }
    }

    public void play(){
        musicCommand("PlayPause");
    }
    public void pause() {
        musicCommand("Pause");
    }
    public void next(){
        musicCommand("Next");
    }
    public void prev(){
        musicCommand("Previous");
    }
    private void musicCommand(final String cmd){
        BackgroundService.RunCommand(context, new BackgroundService.InstanceCallback() {
            @Override
            public void onServiceStart(BackgroundService service) {
                Device device = service.getDevice(deviceId);
                MprisPlugin mpris = (MprisPlugin) device.getPlugin("plugin_mpris");
                if (mpris == null) return;
                mpris.setPlayer(player);
                mpris.sendAction(cmd);
            }
        });
    }
}
