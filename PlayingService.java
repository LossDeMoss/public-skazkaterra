package com.mosesbones.skazkaterra;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.mosesbones.skazkaterra.connecting_people.util.PrettyLover;
import com.mosesbones.skazkaterra.logging.UniversalLogger;
import com.mosesbones.skazkaterra.util.Config;
import com.mosesbones.skazkaterra.util.ListKeeper;

import java.io.IOException;
import java.util.ArrayList;


public class PlayingService extends Service implements MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnPreparedListener,
        MediaPlayer.OnInfoListener, MediaPlayer.OnBufferingUpdateListener, AudioManager.OnAudioFocusChangeListener {

    //
    private static final int NOTIFICATION_ID = 1;
    private PhoneStateListener mPhoneStateListener;
    private Track currentTrack;
    private int resumePosition = 0;
    TelephonyManager mTelephonyManager;
    AudioManager mAudioManager;
    private MediaPlayer mMediaPlayer;
    private Object mMediaSessionManager;
    private MediaSessionCompat mMediaSession;
    public MediaControllerCompat.TransportControls mTransportControls;
    ArrayList<Track> mTracks;
    private boolean ongoingCall, isPrepared, hideBarToo;
    public int currentNum;
    private Notification mNotification;
    private boolean timeRunning = false;
    private Handler mHandler = new Handler();
    private UniversalLogger logger;


    private Runnable timeUpdater = new Runnable() {
        @Override
        public void run() {
            timeRunning = true;
            updateTime();
        }
    };

    //LifeCycle---------------------------------
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences sharedPreferences = getSharedPreferences(Config.PREFERENCES_NAME, 0);
        currentNum = sharedPreferences.getInt(Config.CHOSEN_NUMBER, -1);
        logger = new UniversalLogger(this);
        if (mTracks==null){
            ListKeeper keeper = ListKeeper.init(getBaseContext());
            mTracks = keeper.getTrackList();
        }

        if (currentNum != -1 && currentNum < mTracks.size()) {
            //index is in a valid range
            currentTrack = mTracks.get(currentNum);
        } else {
            stopSelf();
        }

    //Request audio focus
        if (requestAudioFocus() == false) {

        stopSelf();
    }

        if (mMediaSessionManager == null) {
        try {
            initMediaSession();
            initMediaPlayer();
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }

    }
    if (mNotification == null) {
        mNotification = buildNotification(PlaybackStatus.PLAYING);
    }
    startForeground(NOTIFICATION_ID, mNotification);
    if (!timeRunning){
        updateTime();
    }
    //Handle Intent action from MediaSession.TransportControls
    handleIncomingActions(intent);
    sendBroadcast(new Intent(Config.SHOW_KEY));
    return START_STICKY;
    }

    public Track getCurrentTrack(){
        return mTracks.get(currentNum);
    }


    @Override
    public void onCreate() {
        super.onCreate();

        // Perform one-time setup procedures

        // Manage incoming phone calls during playback.
        // Pause MediaPlayer on incoming call,
        // Resume on hangup.
        callStateListener();
        //ACTION_AUDIO_BECOMING_NOISY -- change in audio outputs -- BroadcastReceiver
        registerBecomingNoisyReceiver();
        //Listen for new Audio to play -- BroadcastReceiver
        register_playNewAudio();
        registerUpdateList();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        //mMediaPlayer.reset();
        mMediaSession.release();
        removeNotification();
        return super.onUnbind(intent);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mMediaPlayer != null) {
            stopMedia();
            //mMediaPlayer.reset();
            mMediaPlayer.release();
        }
        removeAudioFocus();
        //Disable the PhoneStateListener
        if (mPhoneStateListener != null) {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        removeNotification();

        //unregister BroadcastReceivers
        unregisterReceiver(becomingNoisyReceiver);
        unregisterReceiver(playNewAudio);
        unregisterReceiver(updateListReceiver);
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        //sendBroadcast(new Intent(SHOW_KEY));
        sendBroadcast(new Intent(Config.TITLES_KEY));
        playMedia();
        mp.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
            @Override
            public void onSeekComplete(MediaPlayer mp) {
                playMedia();
            }
        });
    }

    //Binder Utils---------------------------------
    public class ServiceBinder extends Binder {
        public PlayingService getState (Context context) {
            return PlayingService.this;
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        return new ServiceBinder();
    }


    public int getCurrentPosition(){
        SystemClock.sleep(10);
        return mMediaPlayer.getCurrentPosition();
    }
    public int getDuration(){
        if (isPrepared) {
            SystemClock.sleep(10);
            return mMediaPlayer.getDuration();
        } else {
            return 0;
        }
    }

    public boolean MediaPlayerExist(){
        SystemClock.sleep(10);
        return mMediaPlayer!=null;
    }

    public boolean isPlaying(){
        SystemClock.sleep(10);
        if (mMediaPlayer!=null){
            return mMediaPlayer.isPlaying();
        } else {
            return false;
        }
    }

    //Interfaces implementation---------------------------------
    @Override
    public void onAudioFocusChange(int focusState) {
        //Invoked when the audio focus of the system is updated.
        switch (focusState) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // resume playback
                if (mMediaPlayer == null) {
                    try {
                        initMediaPlayer();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                else if (!mMediaPlayer.isPlaying()) mMediaPlayer.start();
                mMediaPlayer.setVolume(1.0f, 1.0f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time: stop playback and release media player
                if (mMediaPlayer!=null&&mMediaPlayer.isPlaying()) mMediaPlayer.stop();
                //mMediaPlayer.reset();
                mMediaPlayer.pause();
                buildNotification(PlaybackStatus.PAUSED);
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time, but we have to stop
                // playback. We don't release the media player because playback
                // is likely to resume
                if (mMediaPlayer.isPlaying()) mMediaPlayer.pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                if (mMediaPlayer.isPlaying()) mMediaPlayer.setVolume(0.1f, 0.1f);
                break;
        }
    }

    private boolean requestAudioFocus() {
            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int result = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                //Focus gained
                return true;
            }
            //Could not gain focus
            return false;
        }

        private boolean removeAudioFocus() {
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED ==
                    mAudioManager.abandonAudioFocus(this); }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {

    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        //SystemClock.sleep(400);
        //skipToNext();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                Log.d("MediaPlayer Error", "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK " + extra);
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                Log.d("MediaPlayer Error", "MEDIA ERROR SERVER DIED " + extra);
                break;
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                Log.d("MediaPlayer Error", "MEDIA ERROR UNKNOWN " + extra);
                break;
        }
        return true;
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        return false;
    }

    @Override
    public void onSeekComplete(MediaPlayer mp) {}

    //Util methods---------------------------------
    private BroadcastReceiver playNewAudio = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //Called when you need to play another track or if you want to start playing tracks, when nothing is playing

            //Get the new media index form SharedPreferences
            if (currentNum != -1 && currentNum < mTracks.size()) {
                //index is in a valid range
                currentTrack = mTracks.get(currentNum);
            } else {
                stopSelf();
            }

            //A PLAY_NEW_AUDIO action received
            //reset mediaPlayer to play the new Audio
            stopMedia();
            //mMediaPlayer.reset();
            try {
                initMediaPlayer();
            } catch (IOException e) {
                e.printStackTrace();
            }
            updateMetaData();
            buildNotification(PlaybackStatus.PLAYING);
        }
    };

    private void register_playNewAudio() {
        //Register playNewMedia receiver
        IntentFilter filter = new IntentFilter(PlayerActivity.Broadcast_PLAY_NEW_AUDIO);
        registerReceiver(playNewAudio, filter);
    }


    private BroadcastReceiver becomingNoisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //pause audio on ACTION_AUDIO_BECOMING_NOISY
            pauseMedia();
            buildNotification(PlaybackStatus.PAUSED);
        }
    };

    private void registerUpdateList(){
        IntentFilter likeFilter = new IntentFilter(PrettyLover.LIKE_SUCCESS);
        IntentFilter listenedFilter = new IntentFilter(PrettyLover.LISTENED_SUCCESS);
        registerReceiver(updateListReceiver, likeFilter);
        registerReceiver(updateListReceiver, listenedFilter);
    }

    private BroadcastReceiver updateListReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateList();
        }


    };


    private void registerBecomingNoisyReceiver() {
        //register after getting audio focus
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(becomingNoisyReceiver, filter);
    }



    private void removeNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private void updateTime() {
        if ((mMediaPlayer != null)){
            //Update time bar in MainActivity and PlayFragment
            sendBroadcast(new Intent(Config.BAR_KEY));
            if (!currentTrack.getListened()){
                if ((int)(((float) mMediaPlayer.getCurrentPosition() / mMediaPlayer.getDuration()) * 100)>15){
                    //Now we know it's listened and we will not return here
		    //Some business logic
                }
            }
        }
        //btw, it's a every-second cycle
        mHandler.postDelayed(timeUpdater, 1000);
    }

    private void updateList() {
        mTracks = ListKeeper.init(getBaseContext()).getTrackList();
    }

    @SuppressLint("ServiceCast")
    private void initMediaSession() throws RemoteException {
        if (mMediaSessionManager != null) return; //mediaSessionManager exists

        mMediaSessionManager = getApplicationContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
        // Create a new MediaSession
        mMediaSession = new MediaSessionCompat(getApplicationContext(), "AudioPlayer");
        //Get MediaSessions transport controls
        mTransportControls = mMediaSession.getController().getTransportControls();
        //set MediaSession -> ready to receive media commands
        mMediaSession.setActive(true);
        //indicate that the MediaSession handles transport control commands
        // through its MediaSessionCompat.Callback.
        mMediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        //Set mediaSession's MetaData
        updateMetaData();

        // Attach Callback to receive MediaSession updates
        mMediaSession.setCallback(new MediaSessionCompat.Callback() {
            // Implement callbacks
            @Override
            public void onPlay() {
                super.onPlay();
                resumeMedia();
                buildNotification(PlaybackStatus.PLAYING);
                sendBroadcast(new Intent(Config.PLAY_KEY));
                sendBroadcast(new Intent(Config.TITLES_KEY));
                //sendBroadcast(new Intent(SHOW_KEY));
            }

            @Override
            public void onPause() {
                super.onPause();
                pauseMedia();
                buildNotification(PlaybackStatus.PAUSED);
            }

            @Override
            public void onSkipToNext() {
                super.onSkipToNext();
                skipToNext();
                updateMetaData();
                buildNotification(PlaybackStatus.PLAYING);
            }

            @Override
            public void onSkipToPrevious() {
                super.onSkipToPrevious();
                skipToPrevious();
                updateMetaData();
                buildNotification(PlaybackStatus.PLAYING);
            }

            @Override
            public void onStop() {
                super.onStop();
                removeNotification();
                //Stop the service
                stopSelf();
            }

            @Override
            public void onSeekTo(long position) {
                mMediaPlayer.seekTo((int)position);
                SystemClock.sleep(10);
                mMediaPlayer.start();
            }
        });
    }


    private void handleIncomingActions(Intent playbackAction) {
        if (playbackAction == null || playbackAction.getAction() == null) return;

        String actionString = playbackAction.getAction();
        if (actionString.equalsIgnoreCase(Config.ACTION_PLAY)) {
            mTransportControls.play();
            //sendBroadcast(new Intent(SHOW_KEY));
        } else if (actionString.equalsIgnoreCase(Config.ACTION_PAUSE)) {
            mTransportControls.pause();
        } else if (actionString.equalsIgnoreCase(Config.ACTION_NEXT)) {
            mTransportControls.skipToNext();
        } else if (actionString.equalsIgnoreCase(Config.ACTION_PREVIOUS)) {
            mTransportControls.skipToPrevious();
        } else if (actionString.equalsIgnoreCase(Config.ACTION_CLOSE)) {
            SystemClock.sleep(100);
            sendBroadcast(new Intent(Config.HIDE_KEY));
            mTransportControls.pause();
            mTransportControls.stop();
            removeNotification();
        }
    }

   private void initMediaPlayer() throws IOException {
       mMediaPlayer = new MediaPlayer();
       mMediaPlayer.setOnCompletionListener(this);
       mMediaPlayer.setOnErrorListener(this);
       mMediaPlayer.setOnBufferingUpdateListener(this);
       mMediaPlayer.setOnInfoListener(this);
       mMediaPlayer.setOnSeekCompleteListener(this);
       mMediaPlayer.setOnPreparedListener(this);
       mMediaPlayer.reset();

       mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
       try {
           mMediaPlayer.setDataSource(currentTrack.getLink());
       } catch (IOException e) {
           e.printStackTrace();
           stopSelf();
       }
       mMediaPlayer.prepareAsync();
   }

    private void playMedia() {
        if (!mMediaPlayer.isPlaying()) {
            SystemClock.sleep(300);
            mMediaPlayer.start();
            sendBroadcast(new Intent(Config.PLAY_KEY));
            sendBroadcast(new Intent(Config.TITLES_KEY));
        }
    }

    private void stopMedia() {
        if (mMediaPlayer == null) return;
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
            sendBroadcast(new Intent(Config.PLAY_KEY));
            sendBroadcast(new Intent(Config.TITLES_KEY));
            if (hideBarToo){
                sendBroadcast(new Intent(Config.HIDE_KEY));
            }

        }
    }

    private void pauseMedia() {
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
            resumePosition = mMediaPlayer.getCurrentPosition();
            Intent intent = new Intent(Config.PLAY_KEY);
            sendBroadcast(intent);
        }
    }

    private void resumeMedia() {
        if (!mMediaPlayer.isPlaying()) {
            mMediaPlayer.seekTo(resumePosition);
            mMediaPlayer.start();
            Intent intent = new Intent(Config.PLAY_KEY);
            sendBroadcast(intent);
        }
    }


    private void skipToPrevious() {
        currentNum  = getSharedPreferences(Config.PREFERENCES_NAME, 0).getInt(Config.CHOSEN_NUMBER, -1);

        if (currentNum == 0) {
            //if first in playlist
            currentNum = mTracks.size() - 1;
        } else {
            //get previous in playlist
            --currentNum;
        }
        currentTrack = mTracks.get(currentNum);
        SharedPreferences.Editor editor = getSharedPreferences(Config.PREFERENCES_NAME, 0).edit();
        editor.putInt(Config.CHOSEN_NUMBER, currentNum);
        editor.apply();
        stopMedia();
        //reset mediaPlayer
        mMediaPlayer.reset();
        try{initMediaPlayer();}catch (Exception e){}
    }

    private void skipToNext() {
        currentNum = getSharedPreferences(Config.PREFERENCES_NAME, 0).getInt(Config.CHOSEN_NUMBER, -1);
        if (currentNum == mTracks.size() - 1) {
            //if last in playlist
            currentNum = 0;

        } else {
            ++currentNum;

        }
        currentTrack = mTracks.get(currentNum);
        SharedPreferences.Editor editor = getSharedPreferences(Config.PREFERENCES_NAME, 0).edit();
        editor.putInt(Config.CHOSEN_NUMBER, currentNum);
        editor.apply();
        stopMedia();
        mMediaPlayer.reset();
        try{initMediaPlayer();} catch (Exception e){}

    }


    private void callStateListener() {
        // Get the telephony manager
        mTelephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        //Starting listening for PhoneState changes
        // Register the listener with the telephony manager
        // Listen for changes to the device call state.
        mTelephonyManager.listen(new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                switch (state) {
                    //if at least one call exists or the phone is ringing
                    //pause the MediaPlayer
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                    case TelephonyManager.CALL_STATE_RINGING:
                        if (mMediaPlayer != null) {
                            pauseMedia();
                            ongoingCall = true;
                        }
                        break;
                    case TelephonyManager.CALL_STATE_IDLE:
                        // Phone idle. Start playing.
                        if (mMediaPlayer != null) {
                            if (ongoingCall) {
                                ongoingCall = false;
                                resumeMedia();
                            }
                        }
                        break;
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE);
    }
    private void updateMetaData() {
    Bitmap albumArt = BitmapFactory.decodeResource(getResources(),
                R.drawable.birdy); //replace with medias albumArt
        // Update the current metadata
        mMediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentTrack.getAuthor())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentTrack.getAuthor())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTrack.getName())
                .build());
    }

    private PendingIntent playbackAction(int actionNumber) {
        Intent playbackAction = new Intent(getApplicationContext(), PlayingService.class);
        switch (actionNumber) {
            case 0:
                // Play
                playbackAction.setAction(Config.ACTION_PLAY);
                return PendingIntent.getService(getApplicationContext(), actionNumber, playbackAction, 0);
            case 1:
                // Pause
                playbackAction.setAction(Config.ACTION_PAUSE);
                return PendingIntent.getService(getApplicationContext(), actionNumber, playbackAction, 0);
            case 2:
                // Next track
                playbackAction.setAction(Config.ACTION_NEXT);
                return PendingIntent.getService(getApplicationContext(), actionNumber, playbackAction, 0);
            case 3:
                // Previous track
                playbackAction.setAction(Config.ACTION_PREVIOUS);
                return PendingIntent.getService(getApplicationContext(), actionNumber, playbackAction, 0);
            case 4:
                playbackAction.setAction(Config.ACTION_CLOSE);
                return PendingIntent.getService(getApplicationContext(), actionNumber, playbackAction, 0);
            default:
                break;
        }
        return null;
    }

    private Notification buildNotification(PlaybackStatus playbackStatus) {

        /**
         * Notification actions -> playbackAction()
         *  0 -> Play
         *  1 -> Pause
         *  2 -> Next track
         *  3 -> Previous track
         */

        int notificationAction = android.R.drawable.ic_media_play;//needs to be initialized
        PendingIntent play_pauseAction = null;

        //Build a new notification according to the current state of the MediaPlayer
        if (playbackStatus == PlaybackStatus.PLAYING) {
            notificationAction = android.R.drawable.ic_media_pause;
            //create the pause action
            play_pauseAction = playbackAction(1);
        } else if (playbackStatus == PlaybackStatus.PAUSED) {
            notificationAction = android.R.drawable.ic_media_play;
            //create the play action
            play_pauseAction = playbackAction(0);
        }

        Bitmap largeIcon = BitmapFactory.decodeResource(getResources(),
                R.drawable.birdy); //replace with your own image

        // Create a new Notification
        NotificationCompat.Builder notificationBuilder = (NotificationCompat.Builder) new NotificationCompat.Builder(this, Config.CHANNEL_PLAYER_ID)
                // Hide the timestamp
                .setShowWhen(false)
                // Set the Notification style
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        // Attach our MediaSession token

                        // Show our playback controls in the compat view
                        .setShowActionsInCompactView(0, 1))
                // Set the Notification color
                .setColor(getResources().getColor(R.color.colorAccent))
                // Set the large and sma ll icons
                .setLargeIcon(largeIcon)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                // Set Notification content information
                .setContentText(currentTrack.getAuthor())
                .setContentTitle(currentTrack.getName())
                .setContentInfo(currentTrack.getName())
                // Add playback actions
                //.addAction(R.drawable._player_backward_button, "previous", playbackAction(3))
                .addAction(notificationAction, "pause", play_pauseAction)
                //.addAction(R.drawable._player_forward_button, "next", playbackAction(2))
                .addAction(R.drawable.ic_clear_black_24dp, "close", playbackAction(4));


        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notificationBuilder.build());

        return notificationBuilder.build();
    }
}
