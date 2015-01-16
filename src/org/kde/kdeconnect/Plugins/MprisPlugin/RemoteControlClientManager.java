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

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Handler;
import android.os.Message;

import org.kde.kdeconnect.Device;

@SuppressLint("NewApi")
public class RemoteControlClientManager {


    private final String deviceId;
    private final String player;
    private final Context context;
    private ComponentName eventReceiver;
    private RemoteControlClient remoteControlClient;
    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private String song;

    public RemoteControlClientManager(Context context, Device device, String player){
        this.context = context;
        this.deviceId = device.getDeviceId();
        this.player = player;

        audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        focusListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {

            }
        };

        registerRemoteClient();

        final MprisPlugin mpris = (MprisPlugin)device.getPlugin("plugin_mpris");
        if (mpris != null) {
            mpris.setPlayerStatusUpdatedHandler("remoteclient", new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    String song = mpris.getCurrentSong();
                    boolean isPlaying = mpris.isPlaying();
                    updateStatus(song, isPlaying);
                }
            });
        }
    }
    private void registerRemoteClient(){
        eventReceiver = new ComponentName(context.getPackageName(), MusicControlReceiver.class.getName());
        audioManager.registerMediaButtonEventReceiver(eventReceiver);
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.putExtra("deviceId", deviceId);
        mediaButtonIntent.putExtra("player", player);
        mediaButtonIntent.setComponent(eventReceiver);
        PendingIntent mediaPendingIntent = PendingIntent.getBroadcast(context, 0, mediaButtonIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        remoteControlClient = new RemoteControlClient(mediaPendingIntent);
        audioManager.registerRemoteControlClient(remoteControlClient);
        remoteControlClient.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                        | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                        | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS
                        | RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                        | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
        );
    }
    private void updateMetadata(){
        RemoteControlClient.MetadataEditor metatdataEditor = remoteControlClient.editMetadata(true);
        metatdataEditor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, song);
        metatdataEditor.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, player);
        metatdataEditor.apply();
    }
    public void unregisterRemoteClient() {
         audioManager.unregisterMediaButtonEventReceiver(eventReceiver);
         RemoteControlHelper.unregisterRemoteControlClient(audioManager, remoteControlClient);
         remoteControlClient = null;
    }
    public void updateStatus(String songName, boolean isPlaying){
        song = songName;
        if(isPlaying){
            registerRemoteClient();
            audioManager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if(remoteControlClient!=null)
                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
            updateMetadata();
        }else{
            audioManager.abandonAudioFocus(focusListener);
            if(remoteControlClient!=null)
                remoteControlClient.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
        }
    }
}
