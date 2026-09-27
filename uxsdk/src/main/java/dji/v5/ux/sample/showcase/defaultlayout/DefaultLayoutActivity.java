/*
 * Copyright (c) 2018-2020 DJI
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package dji.v5.ux.sample.showcase.defaultlayout;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.NotificationCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.google.gson.Gson;

import dji.sampleV5.aircraft.data.FlightControlState;
import dji.sampleV5.aircraft.data.GimbalControlState;
import dji.sampleV5.aircraft.models.VirtualStickVM;
import dji.sdk.keyvalue.key.FlightControllerKey;
import dji.sdk.keyvalue.key.CameraKey;
import dji.sdk.keyvalue.key.GimbalKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.key.ProductKey;
import dji.sdk.keyvalue.value.common.CameraLensType;
import dji.sdk.keyvalue.value.common.EmptyMsg;
import dji.sdk.keyvalue.value.flightcontroller.HeightAboveSeaLevelMsg;

import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.sdk.keyvalue.value.common.LocationCoordinate2D;
import dji.sdk.keyvalue.value.common.Velocity3D;
import dji.v5.common.callback.CommonCallbacks;
import dji.v5.common.error.IDJIError;
import dji.v5.common.utils.GpsUtils;
import dji.v5.common.utils.RxUtil;
import dji.v5.manager.KeyManager;
import dji.v5.manager.datacenter.MediaDataCenter;
import dji.v5.manager.datacenter.livestream.LiveStreamSettings;
import dji.v5.manager.datacenter.livestream.LiveStreamType;
import dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode;
import dji.v5.manager.datacenter.livestream.StreamQuality;
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings;
import dji.v5.manager.datacenter.livestream.settings.RtspSettings;
import dji.v5.manager.interfaces.ICameraStreamManager;
import dji.v5.manager.interfaces.ILiveStreamManager;
import dji.v5.manager.interfaces.IMediaDataCenter;
import dji.v5.network.DJINetworkManager;
import dji.v5.network.IDJINetworkStatusListener;
import dji.v5.utils.common.JsonUtil;
import dji.v5.utils.common.LogPath;
import dji.v5.utils.common.LogUtils;
import dji.v5.ux.BuildConfig;
import dji.v5.ux.R;
import dji.v5.ux.accessory.RTKStartServiceHelper;
import dji.v5.ux.cameracore.widget.autoexposurelock.AutoExposureLockWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.CameraControlsWidget;
import dji.v5.ux.cameracore.widget.cameracontrols.lenscontrol.LensControlWidget;
import dji.v5.ux.cameracore.widget.focusexposureswitch.FocusExposureSwitchWidget;
import dji.v5.ux.cameracore.widget.focusmode.FocusModeWidget;
import dji.v5.ux.cameracore.widget.fpvinteraction.FPVInteractionWidget;
import dji.v5.ux.core.base.SchedulerProvider;
import dji.v5.ux.core.communication.BroadcastValues;
import dji.v5.ux.core.communication.GlobalPreferenceKeys;
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore;
import dji.v5.ux.core.communication.UXKeys;
import dji.v5.ux.core.extension.ViewExtensions;
import dji.v5.ux.core.panel.systemstatus.SystemStatusListPanelWidget;
import dji.v5.ux.core.panel.topbar.TopBarPanelWidget;
import dji.v5.ux.core.util.CameraUtil;
import dji.v5.ux.core.util.DataProcessor;
import dji.v5.ux.core.util.ViewUtil;
import dji.v5.ux.core.widget.fpv.FPVWidget;
import dji.v5.ux.core.widget.hsi.HorizontalSituationIndicatorWidget;
import dji.v5.ux.core.widget.hsi.PrimaryFlightDisplayWidget;
import dji.v5.ux.core.widget.setting.SettingWidget;
import dji.v5.ux.core.widget.systemstatus.SystemStatusWidget;
import dji.v5.ux.gimbal.GimbalFineTuneWidget;
import dji.v5.ux.map.MapWidget;
import dji.v5.ux.mapkit.core.maps.DJIUiSettings;
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget;
import dji.v5.ux.visualcamera.zoom.FocalZoomWidget;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.jetbrains.annotations.NotNull;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class DefaultLayoutActivity extends AppCompatActivity {
    static boolean telemok = false;
    static boolean wpenable = false;
    public List<Object[]> Waypoints = new ArrayList<>();


    private final String TAG = LogUtils.getTag(this);

    protected FPVWidget primaryFpvWidget;
    protected FPVInteractionWidget fpvInteractionWidget;
    protected FPVWidget secondaryFPVWidget;
    protected SystemStatusListPanelWidget systemStatusListPanelWidget;
    protected LensControlWidget lensControlWidget;
    protected AutoExposureLockWidget autoExposureLockWidget;
    protected FocusModeWidget focusModeWidget;
    protected FocusExposureSwitchWidget focusExposureSwitchWidget;
    protected CameraControlsWidget cameraControlsWidget;
    protected HorizontalSituationIndicatorWidget horizontalSituationIndicatorWidget;
    protected PrimaryFlightDisplayWidget pfvFlightDisplayWidget;
    protected CameraVisiblePanelWidget visualCameraPanel;
    protected FocalZoomWidget focalZoomWidget;
    protected SettingWidget settingWidget;
    protected MapWidget mapWidget;
    protected TopBarPanelWidget topBarPanel;
    protected ConstraintLayout fpvParentView;
    private DrawerLayout mDrawerLayout;
    private TextView gimbalAdjustDone;
    private GimbalFineTuneWidget gimbalFineTuneWidget;
    private ComponentIndexType lastDevicePosition = ComponentIndexType.UNKNOWN;
    private CameraLensType lastLensType = CameraLensType.UNKNOWN;


    private CompositeDisposable compositeDisposable;
    private final DataProcessor<CameraSource> cameraSourceProcessor = DataProcessor.create(new CameraSource(ComponentIndexType.UNKNOWN,
            CameraLensType.UNKNOWN));

    private Disposable flightControlDisposable;
    MutableLiveData<FlightControlState> flightControlState = new MutableLiveData<>();
    private Disposable gimbalControlDisposable;
    MutableLiveData<GimbalControlState> gimbalControlState = new MutableLiveData<>();
    private boolean lockedyaw = false;
    private Button mDisableWaypoint;
    private Button mEnableWaypoint;
    private Button mMissionStart;
    private Button mMissionStop;
    private Button mMissionSegment;
    public String missionId = "0";
    private int wpcounter = 0;

    public Handler handler;

    public Handler handlerlocalization;
    private Handler handlercontrol;
    private VirtualStickVM virtualStickVM;

    // Must match a vehicle id in the Ikaros database: the server files
    // telemetry against it, and the video player asks the media server for
    // the path live/<vehicleId>. dev01 currently has vehicle 3.
    private String vehicleId = "3";
    // The deployment's INTERNAL_API_KEY. Generated per environment; comes
    // from IKAROS_TOKEN in secrets.properties.
    private String fbdevtoken = BuildConfig.IKAROS_TOKEN;
    // Attribution only - the fbauthtoken above is what authenticates.
    private String userId= "1";

    // Ikaros API. HTTPS on 443 through the load balancer; there is no public
    // port 3000 any more, and the old :3000 URLs cannot connect.
    private String ikarosHost = "ikaros-dev01.autonoma-solutions.eu";
    private String apiBase = "https://" + ikarosHost;

    // RTMP ingest is a DIFFERENT hostname on purpose. The API is behind an
    // application load balancer, which only speaks HTTP and cannot carry
    // RTMP, so video goes to a network load balancer instead.
    private String rtmpHost = "rtmp-dev01.autonoma-solutions.eu";
    private String rtmpUser = "ikaros";
    private String rtmpPass = BuildConfig.RTMP_PASS;

    // ── Camera pose, for geolocation ──────────────────────────────────────
    //
    // 35 mm-equivalent focal length of this airframe's WIDE camera at 1x. The
    // server's calibration is expressed against it (camera_profiles.json,
    // dji_mavic_wide, ref_focal_mm 24), and PARM recovers the zoom as
    // focal_mm / ref_focal_mm. Keep the two in step: changing the profile on
    // the server without changing this scales every ray wrongly.
    private double wideFocalMm = 24.0;
    // Latest gimbal attitude, in the gimbal's own frame. yaw is NOT an azimuth
    // until corrected by imuCoordinateTran - see listenCameraPose().
    private volatile Double gimbalYawRaw = null;
    private volatile Double gimbalPitch = null;
    private volatile Double gimbalRoll = null;
    // Radians. The offset between the gimbal's yaw frame and the aircraft's,
    // which is what turns the raw yaw into a true-north azimuth. The uxsdk's
    // own HSI does exactly this (HSIWidgetModel: yaw + tran * RAD_TO_DEG).
    private volatile Double imuCoordinateTran = null;
    // 1.0 at wide. Multiplies wideFocalMm to give the focal length actually in
    // use, so zooming mid-flight narrows the modelled field of view.
    private volatile Double zoomRatio = null;
    private Disposable gimbalAttitudeDisposable;
    private Disposable imuTranDisposable;
    private Disposable zoomRatioDisposable;

    // Is a mission running because the pilot pressed Start? The old build
    // auto-started whatever get_mission returned, which is why the button had
    // to be removed from the app: there was no moment the pilot chose.
    private volatile boolean missionStarted = false;
    // Is a marked segment open? Mirrors the server, which is authoritative -
    // this only drives the button's label.
    private volatile boolean segmentOpen = false;

    // Unrelated Flask service, still on warden - left alone.
    private String wardenIP= "warden.autonoma-solutions.eu";
    ILiveStreamManager liveStreamManager;

    private ICameraStreamManager streamManager;
    private UdpSender udpSender;
    // Telemetry at 5 Hz. The gimbal and the zoom are what Ikaros geolocates
    // through, and they move while the aircraft is still - at 0.5 Hz a pan
    // across a scene was described by two samples, and every detection between
    // them was placed through a camera pointing somewhere else. The server
    // interpolates poses by timestamp, so what it can resolve is bounded by
    // this interval.
    //
    // Fire-and-forget: OkHttp's enqueue() does not block, so a slow response
    // cannot hold up the next sample.
    private static final long TELEMETRY_INTERVAL_MS = 200;
    // Mission polling stays slow. It is a question about a database row, not a
    // measurement, and at 5 Hz it would be 18,000 pointless requests an hour -
    // enough to matter to the rate limiter if any of them start failing.
    private static final long MISSION_POLL_INTERVAL_MS = 2000;

    private Runnable runnable = new Runnable() {
        public void run() {
            if (DefaultLayoutActivity.telemok) {
                try {
                    if (!DefaultLayoutActivity.this.missionId.equals("0")) {
                        DefaultLayoutActivity.this.postVehicleData();
                    }
                } catch (JSONException | IOException e) {
                    e.printStackTrace();
                }
            }
            DefaultLayoutActivity.this.handler.postDelayed(this, TELEMETRY_INTERVAL_MS);
        }
    };

    // Split out of the telemetry loop: these two answer "is there a mission"
    // and "am I alive", neither of which is worth asking five times a second.
    private Runnable missionPollRunnable = new Runnable() {
        public void run() {
            if (DefaultLayoutActivity.telemok) {
                try {
                    // Only until the pilot starts it. After that the mission is
                    // known, and re-reading it would overwrite the loaded
                    // waypoints from under a flight in progress.
                    if (!DefaultLayoutActivity.this.missionStarted) {
                        DefaultLayoutActivity.this.getMission();
                    }
                    DefaultLayoutActivity.this.postHeartbeat();
                    // JSONException only: neither call declares IOException
                    // now that postVehicleData has its own loop, and catching
                    // an exception that cannot be thrown does not compile.
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            DefaultLayoutActivity.this.handler.postDelayed(this, MISSION_POLL_INTERVAL_MS);
        }
    };
    Runnable secondRunnable = new Runnable() {
        public void run() {
            try {
                DefaultLayoutActivity.this.go2target();
            } catch (JSONException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            DefaultLayoutActivity.this.handler.postDelayed(this, 100);
        }
    };

    Runnable thirdRunnable = new Runnable() {
        public void run() {
            DefaultLayoutActivity.this.postDJITelemetry();
            DefaultLayoutActivity.this.handler.postDelayed(this, 1000);
        }
    };
    private final IDJINetworkStatusListener networkStatusListener = isNetworkAvailable -> {
        if (isNetworkAvailable) {
            LogUtils.d(TAG, "isNetworkAvailable=" + true);
            RTKStartServiceHelper.INSTANCE.startRtkService(false);
        }
    };
    private final ICameraStreamManager.AvailableCameraUpdatedListener availableCameraUpdatedListener = new ICameraStreamManager.AvailableCameraUpdatedListener() {
        @Override
        public void onAvailableCameraUpdated(@NonNull List<ComponentIndexType> availableCameraList) {
            runOnUiThread(() -> updateFPVWidgetSource(availableCameraList));
        }

        @Override
        public void onCameraStreamEnableUpdate(@NonNull Map<ComponentIndexType, Boolean> cameraStreamEnableMap) {
            //
        }
    };

    //endregion

    //region Lifecycle
    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.uxsdk_activity_default_layout);

        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        vehicleId = prefs.getString("vehicleId", vehicleId);
        fbdevtoken = prefs.getString("fbdevtoken", fbdevtoken);
        userId = prefs.getString("userId", userId);
        wardenIP = prefs.getString("wardenIP", wardenIP);
        ikarosHost = prefs.getString("ikarosHost", ikarosHost);
        apiBase = "https://" + ikarosHost;
        rtmpHost = prefs.getString("rtmpHost", rtmpHost);
        rtmpUser = prefs.getString("rtmpUser", rtmpUser);
        rtmpPass = prefs.getString("rtmpPass", rtmpPass);
        getProductUUIDAndShowToast();
        fpvParentView = findViewById(R.id.fpv_holder);
        mDrawerLayout = findViewById(R.id.root_view);
        topBarPanel = findViewById(R.id.panel_top_bar);
        settingWidget = topBarPanel.getSettingWidget();
        primaryFpvWidget = findViewById(R.id.widget_primary_fpv);
        fpvInteractionWidget = findViewById(R.id.widget_fpv_interaction);
        secondaryFPVWidget = findViewById(R.id.widget_secondary_fpv);
        systemStatusListPanelWidget = findViewById(R.id.widget_panel_system_status_list);
        lensControlWidget = findViewById(R.id.widget_lens_control);
        visualCameraPanel = findViewById(R.id.panel_visual_camera);
        autoExposureLockWidget = findViewById(R.id.widget_auto_exposure_lock);
        focusModeWidget = findViewById(R.id.widget_focus_mode);
        focusExposureSwitchWidget = findViewById(R.id.widget_focus_exposure_switch);
        pfvFlightDisplayWidget = findViewById(R.id.widget_fpv_flight_display_widget);
        focalZoomWidget = findViewById(R.id.widget_focal_zoom);
        cameraControlsWidget = findViewById(R.id.widget_camera_controls);
        horizontalSituationIndicatorWidget = findViewById(R.id.widget_horizontal_situation_indicator);
        gimbalAdjustDone = findViewById(R.id.fpv_gimbal_ok_btn);
        gimbalFineTuneWidget = findViewById(R.id.setting_menu_gimbal_fine_tune);
        mapWidget = findViewById(R.id.widget_map);
        Handler handler2 = new Handler();
        this.handler = handler2;
        handler2.postDelayed(this.runnable, 1000);
        handler2.postDelayed(this.missionPollRunnable, 1000);
        // postDJITelemetry (port 5100) is disabled — endpoint no longer in use
        // Handler handler3 = new Handler();
        // this.handlerlocalization = handler3;
        // handler3.postDelayed(this.thirdRunnable, 100);
        listenFlightControlState();
        listenGimbalControlState();
        listenCameraPose();
        this.mEnableWaypoint = findViewById(R.id.enable_waypoint);
        this.mDisableWaypoint =findViewById(R.id.disable_waypoint);
        this.mMissionStart = findViewById(R.id.mission_start);
        this.mMissionStop = findViewById(R.id.mission_stop);
        this.mMissionSegment = findViewById(R.id.mission_segment);
        initClickListener();
        MediaDataCenter.getInstance().getCameraStreamManager().addAvailableCameraUpdatedListener(availableCameraUpdatedListener);
        primaryFpvWidget.setOnFPVStreamSourceListener((devicePosition, lensType) -> cameraSourceProcessor.onNext(new CameraSource(devicePosition, lensType)));

        //小surfaceView放置在顶部，避免被大的遮挡
        secondaryFPVWidget.setSurfaceViewZOrderOnTop(true);
        secondaryFPVWidget.setSurfaceViewZOrderMediaOverlay(true);


//        mapWidget.initGoogleMap(map -> {
//            DJIUiSettings uiSetting = map.getUiSettings();
//            if (uiSetting != null) {
//                uiSetting.setZoomControlsEnabled(false); // hide zoom widget
//            }
//        });
        mapWidget.initMapLibreMap(getApplicationContext(), map -> {
            DJIUiSettings uiSetting = map.getUiSettings();
            if (uiSetting != null) { uiSetting.setZoomControlsEnabled(false);//hide zoom widget
            } });

        mapWidget.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        //实现RTK监测网络，并自动重连机制
        DJINetworkManager.getInstance().addNetworkStatusListener(networkStatusListener);
        virtualStickVM = new ViewModelProvider(this).get(VirtualStickVM.class);



        streamManager = MediaDataCenter.getInstance().getCameraStreamManager();

        // Keep decoder alive even if no preview surface is attached
        streamManager.setKeepAliveDecoding(true);

        try {
            // Replace with IP of the receiving machine and UDP port
            udpSender = new UdpSender("192.168.1.13", 8554);
        } catch (Exception e) {
            e.printStackTrace();
        }

//        streamManager.addAvailableCameraUpdatedListener(new ICameraStreamManager.AvailableCameraUpdatedListener() {
//            @Override
//            public void onAvailableCameraUpdated(@NonNull List<ComponentIndexType> availableCameraList) {
//                if (availableCameraList == null || availableCameraList.isEmpty()) return;
//
//                // Example: pick first available camera
//                ComponentIndexType cameraIndex = availableCameraList.get(0);
//
//                streamManager.addReceiveStreamListener(cameraIndex,
//                        (buffer, offset, length, streamInfo) -> {
//                            if (udpSender != null) {
//                                udpSender.send(buffer, offset, length);
//                            }
//                        }
//                );
//            }
//
//            @Override
//            public void onCameraStreamEnableUpdate(Map<ComponentIndexType, Boolean> cameraMap) {
//                if (cameraMap == null || cameraMap.isEmpty()) return;
//
//                // pick the first enabled camera
//                for (Map.Entry<ComponentIndexType, Boolean> entry : cameraMap.entrySet()) {
//                    if (Boolean.TRUE.equals(entry.getValue())) {
//                        ComponentIndexType cameraIndex = entry.getKey();
//
//                        // Now attach stream listener
//                        streamManager.addReceiveStreamListener(cameraIndex,
//                                (buffer, offset, length, streamInfo) -> {
//                                    if (udpSender != null) {
//                                        udpSender.send(buffer, offset, length);
//                                    }
//                                });
//                        break;
//                    }
//                }
//            }
//        });


        liveStreamManager = MediaDataCenter.getInstance().getLiveStreamManager();
        liveStreamManager.setLiveStreamSettings(
                new LiveStreamSettings.Builder()
                        .setRtmpSettings(new RtmpSettings.Builder().setUrl(buildRtmpUrl()).build())
//                        .setRtspSettings(
//                                new RtspSettings.Builder()
//                                        .setUserName("autonoma")
//                                        .setPassWord("auto2025")
//                                        .setPort(8554)
//                                        .build()
//                        )
                        .setLiveStreamType(LiveStreamType.RTMP)

                        .build()
        );
        liveStreamManager.setCameraIndex(ComponentIndexType.find(0));
// Set stream quality, bitrate mode, and exact bitrate
        liveStreamManager.setLiveStreamQuality(StreamQuality.FULL_HD);
// liveStreamManager.setLiveVideoBitrateMode(LiveVideoBitrateMode.AUTO);
//     liveStreamManager.setLiveVideoBitrate(3000000);
        // Configured, NOT started. Publishing begins when the pilot presses
        // Start, together with the mission going InProgress - so the recording
        // the report is later cut from begins at the moment the mission did,
        // rather than whenever the tablet happened to be switched on.
    }

    /**
     * Begin publishing to live/&lt;vehicleId&gt;.
     *
     * Paired with startMission(): the server starts the detector when the
     * mission goes InProgress, and it needs something publishing to attach to.
     */
    private void startLiveStream() {
        if (liveStreamManager == null) {
            showToast("Live stream unavailable");
            return;
        }
        if (liveStreamManager.isStreaming()) {
            return;
        }
        liveStreamManager.startStream(new CommonCallbacks.CompletionCallback() {
            @Override
            public void onSuccess() {
                Log.i("RTMP", "Stream started");
            }

            @Override
            public void onFailure(IDJIError error) {
                Log.e("RTMP", "Stream failed: " + error.description());
                showToast("Stream failed: " + error.description());
            }
        });
    }

    private void isGimableAdjustClicked(BroadcastValues broadcastValues) {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        }
        horizontalSituationIndicatorWidget.setVisibility(View.GONE);
        if (gimbalFineTuneWidget != null) {
            gimbalFineTuneWidget.setVisibility(View.VISIBLE);
        }
    }

    private void initClickListener() {
        secondaryFPVWidget.setOnClickListener(v -> swapVideoSource());

        if (settingWidget != null) {
            settingWidget.setOnClickListener(v -> toggleRightDrawer());
        }

        // Setup top bar state callbacks
        SystemStatusWidget systemStatusWidget = topBarPanel.getSystemStatusWidget();
        if (systemStatusWidget != null) {
            systemStatusWidget.setOnClickListener(v -> ViewExtensions.toggleVisibility(systemStatusListPanelWidget));
        }

        gimbalAdjustDone.setOnClickListener(view -> {
            horizontalSituationIndicatorWidget.setVisibility(View.VISIBLE);
            if (gimbalFineTuneWidget != null) {
                gimbalFineTuneWidget.setVisibility(View.GONE);
            }

        });
        this.mEnableWaypoint.setOnClickListener(v -> wpenablefunc());
        this.mDisableWaypoint.setOnClickListener(v -> wpdisablefunc());
        this.mMissionStart.setOnClickListener(v -> startMission());
        this.mMissionStop.setOnClickListener(v -> stopMission());
        this.mMissionSegment.setOnClickListener(v -> toggleSegment());
    }

    private void toggleRightDrawer() {
        mDrawerLayout.openDrawer(GravityCompat.END);
    }


    /**
     * RTMP publish URL for this aircraft.
     *
     * Credentials go in the QUERY STRING. mediamtx silently ignores the
     * rtmp://user:pass@host form and answers "authentication failed" with no
     * further detail, which is a long afternoon if you do not know.
     *
     * The path is live/<vehicleId> because that is exactly what the Ikaros
     * player subscribes to; any other path publishes a stream nothing shows.
     */
    private String buildRtmpUrl() {
        // encode(String, String) rather than encode(String, Charset): the
        // Charset overload is API 33+, and this has to run on older tablets.
        String user = rtmpUser;
        String pass = rtmpPass;
        try {
            user = URLEncoder.encode(rtmpUser, "UTF-8");
            pass = URLEncoder.encode(rtmpPass, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always present; fall back to the raw values, which are
            // generated without special characters anyway.
            Log.w("RTMP", "UTF-8 unavailable, sending credentials unencoded", e);
        }
        return "rtmp://" + rtmpHost + ":1935/live/" + vehicleId
                + "?user=" + user + "&pass=" + pass;
    }

    private void stopLiveStream() {
        if (liveStreamManager != null && liveStreamManager.isStreaming()) {
            liveStreamManager.stopStream(new CommonCallbacks.CompletionCallback() {
                @Override
                public void onSuccess() {
                    Log.i("RTMP", "Stream stopped");
                }

                @Override
                public void onFailure(IDJIError error) {
                    Log.e("RTMP", "Stream stop failed: " + error.description());
                }
            });
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopLiveStream();
    }

    @Override
    protected void onDestroy() {
        Runnable runnable2;
        Runnable runnable3;
        stopLiveStream();
        Handler handler2 = this.handler;
        if (!(handler2 == null || (runnable3 = this.runnable) == null)) {
            handler2.removeCallbacks(runnable3);
        }
        Handler handler3 = this.handlercontrol;
        if (!(handler3 == null || (runnable2 = this.secondRunnable) == null)) {
            handler3.removeCallbacks(runnable2);
        }
        // thirdRunnable (postDJITelemetry) is disabled
        Disposable disposable = this.flightControlDisposable;
        if (disposable != null && !disposable.isDisposed()) {
            this.flightControlDisposable.dispose();
        }
        if (handler2 != null && this.missionPollRunnable != null) {
            handler2.removeCallbacks(this.missionPollRunnable);
        }
        // The camera-pose listeners. Left subscribed they outlive the activity
        // and keep writing into fields nothing reads.
        for (Disposable d : new Disposable[]{
                this.gimbalAttitudeDisposable, this.imuTranDisposable, this.zoomRatioDisposable}) {
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        }
        Disposable gimbaldisposable = this.gimbalControlDisposable;
        if (gimbaldisposable != null && !gimbaldisposable.isDisposed()) {
            this.gimbalControlDisposable.dispose();
        }
        mapWidget.onDestroy();
        MediaDataCenter.getInstance().getCameraStreamManager().removeAvailableCameraUpdatedListener(availableCameraUpdatedListener);
        DJINetworkManager.getInstance().removeNetworkStatusListener(networkStatusListener);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapWidget.onResume();
        compositeDisposable = new CompositeDisposable();
        compositeDisposable.add(systemStatusListPanelWidget.closeButtonPressed()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(pressed -> {
                    if (pressed) {
                        ViewExtensions.hide(systemStatusListPanelWidget);
                    }
                }));

        compositeDisposable.add(cameraSourceProcessor.toFlowable()
                .observeOn(SchedulerProvider.io())
                .throttleLast(500, TimeUnit.MILLISECONDS)
                .subscribeOn(SchedulerProvider.io())
                .subscribe(result -> runOnUiThread(() -> onCameraSourceUpdated(result.devicePosition, result.lensType)))
        );
        compositeDisposable.add(ObservableInMemoryKeyedStore.getInstance()
                .addObserver(UXKeys.create(GlobalPreferenceKeys.GIMBAL_ADJUST_CLICKED))
                .observeOn(SchedulerProvider.ui())
                .subscribe(this::isGimableAdjustClicked));
        ViewUtil.setKeepScreen(this, true);
    }

    @Override
    protected void onPause() {
        if (compositeDisposable != null) {
            compositeDisposable.dispose();
            compositeDisposable = null;
        }
        mapWidget.onPause();
        super.onPause();
        ViewUtil.setKeepScreen(this, false);
    }
    //endregion


    private void updateFPVWidgetSource(List<ComponentIndexType> availableCameraList) {
        LogUtils.i(TAG, JsonUtil.toJson(availableCameraList));
        if (availableCameraList == null) {
            return;
        }

        ArrayList<ComponentIndexType> cameraList = new ArrayList<>(availableCameraList);

        //没有数据
        if (cameraList.isEmpty()) {
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        //仅一路数据
        if (cameraList.size() == 1) {
            primaryFpvWidget.updateVideoSource(availableCameraList.get(0));
            secondaryFPVWidget.setVisibility(View.GONE);
            return;
        }

        //大于两路数据
        ComponentIndexType primarySource = getSuitableSource(cameraList, ComponentIndexType.LEFT_OR_MAIN);
        primaryFpvWidget.updateVideoSource(primarySource);
        cameraList.remove(primarySource);

        ComponentIndexType secondarySource = getSuitableSource(cameraList, ComponentIndexType.FPV);
        secondaryFPVWidget.updateVideoSource(secondarySource);

        secondaryFPVWidget.setVisibility(View.VISIBLE);
    }

    private ComponentIndexType getSuitableSource(List<ComponentIndexType> cameraList, ComponentIndexType defaultSource) {
        if (cameraList.contains(ComponentIndexType.LEFT_OR_MAIN)) {
            return ComponentIndexType.LEFT_OR_MAIN;
        } else if (cameraList.contains(ComponentIndexType.RIGHT)) {
            return ComponentIndexType.RIGHT;
        } else if (cameraList.contains(ComponentIndexType.UP)) {
            return ComponentIndexType.UP;
        } else if (cameraList.contains(ComponentIndexType.PORT_1)) {
            return ComponentIndexType.PORT_1;
        } else if (cameraList.contains(ComponentIndexType.PORT_2)) {
            return ComponentIndexType.PORT_2;
        } else if (cameraList.contains(ComponentIndexType.PORT_3)) {
            return ComponentIndexType.PORT_4;
        } else if (cameraList.contains(ComponentIndexType.PORT_4)) {
            return ComponentIndexType.PORT_4;
        } else if (cameraList.contains(ComponentIndexType.VISION_ASSIST)) {
            return ComponentIndexType.VISION_ASSIST;
        }
        return defaultSource;
    }

    private void onCameraSourceUpdated(ComponentIndexType devicePosition, CameraLensType lensType) {
        LogUtils.i(LogPath.SAMPLE, "onCameraSourceUpdated", devicePosition, lensType);
        if (devicePosition == lastDevicePosition && lensType == lastLensType) {
            return;
        }
        lastDevicePosition = devicePosition;
        lastLensType = lensType;
        updateViewVisibility(devicePosition, lensType);
        updateInteractionEnabled();
        //如果无需使能或者显示的，也就没有必要切换了。
        if (fpvInteractionWidget.isInteractionEnabled()) {
            fpvInteractionWidget.updateCameraSource(devicePosition, lensType);
        }
        if (lensControlWidget.getVisibility() == View.VISIBLE) {
            lensControlWidget.updateCameraSource(devicePosition, lensType);
        }

        if (visualCameraPanel.getVisibility() == View.VISIBLE) {
            visualCameraPanel.updateCameraSource(devicePosition, lensType);
        }
        if (autoExposureLockWidget.getVisibility() == View.VISIBLE) {
            autoExposureLockWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focusModeWidget.getVisibility() == View.VISIBLE) {
            focusModeWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focusExposureSwitchWidget.getVisibility() == View.VISIBLE) {
            focusExposureSwitchWidget.updateCameraSource(devicePosition, lensType);
        }
        if (cameraControlsWidget.getVisibility() == View.VISIBLE) {
            cameraControlsWidget.updateCameraSource(devicePosition, lensType);
        }
        if (focalZoomWidget.getVisibility() == View.VISIBLE) {
            focalZoomWidget.updateCameraSource(devicePosition, lensType);
        }
        if (horizontalSituationIndicatorWidget.getVisibility() == View.VISIBLE) {
            horizontalSituationIndicatorWidget.updateCameraSource(devicePosition, lensType);
        }
    }

    private void updateViewVisibility(ComponentIndexType devicePosition, CameraLensType lensType) {
        //只在fpv下显示
        pfvFlightDisplayWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.VISIBLE : View.INVISIBLE);

        //fpv下不显示
        lensControlWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        visualCameraPanel.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        autoExposureLockWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focusModeWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focusExposureSwitchWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        cameraControlsWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        focalZoomWidget.setVisibility(CameraUtil.isFPVTypeView(devicePosition) ? View.INVISIBLE : View.VISIBLE);
        horizontalSituationIndicatorWidget.setSimpleModeEnable(CameraUtil.isFPVTypeView(devicePosition));

    }

    /**
     * Swap the video sources of the FPV and secondary FPV widgets.
     */
    private void swapVideoSource() {
        ComponentIndexType primarySource = primaryFpvWidget.getWidgetModel().getCameraIndex();
        ComponentIndexType secondarySource = secondaryFPVWidget.getWidgetModel().getCameraIndex();
        //两个source都存在的情况下才进行切换
        if (primarySource != ComponentIndexType.UNKNOWN && secondarySource != ComponentIndexType.UNKNOWN) {
            primaryFpvWidget.updateVideoSource(secondarySource);
            secondaryFPVWidget.updateVideoSource(primarySource);
        }
    }

    private void updateInteractionEnabled() {
        fpvInteractionWidget.setInteractionEnabled(!CameraUtil.isFPVTypeView(primaryFpvWidget.getWidgetModel().getCameraIndex()));
    }

    private static class CameraSource {
        ComponentIndexType devicePosition;
        CameraLensType lensType;

        public CameraSource(ComponentIndexType devicePosition, CameraLensType lensType) {
            this.devicePosition = devicePosition;
            this.lensType = lensType;
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.END)) {
            mDrawerLayout.closeDrawers();
        } else {
            super.onBackPressed();
        }
    }
    /* access modifiers changed from: private */
    public void showToast(final String toastMsg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(DefaultLayoutActivity.this.getApplicationContext(), toastMsg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void getMission() {
        Log.d("TAG Get Mission", "initiated");

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                // attended=1: a human is holding this tablet and will decide
                // when to fly. Without it the server withholds an unscheduled
                // mission - that gate exists to stop an unattended poller
                // launching one, and the pilot's presence is the exception it
                // stands in for. Withheld means the Start button can never
                // appear, because the mission it would start is invisible.
                .url(apiBase+"/api/android_app/vehicle/mission/get_mission?vehicleId="+vehicleId+"&attended=1")
                .get()
                .addHeader("accept", "*/*")
                .addHeader("fbauthtoken", fbdevtoken)
                .addHeader("userid", userId)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                call.cancel();
                Log.e("TAG Get Mission", "Request failed", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.body() == null) {
                    Log.e("TAG Get Mission", "Empty response body");
                    return;
                }

                int statusCode = response.code();
                String res = response.body().string();
                Log.d("TAG Get Mission", "Response: " + res);

                if (statusCode != 200) {
                    Log.d("TAG Get Mission", "Non-200 response: " + statusCode);
                    return;
                }

                try {
                    JSONObject jsonObject = new JSONObject(res);
                    DefaultLayoutActivity.this.missionId = jsonObject.optString("id", "");

                    if (!DefaultLayoutActivity.this.missionId.isEmpty()) {
                        // Load it; do NOT start it. This used to post
                        // InProgress the instant a mission appeared, so the
                        // mission began the moment the tablet noticed it and
                        // there was no point at which the pilot chose - which
                        // is why the Start button had to be taken out. Starting
                        // is now startMission(), on a press.
                        JSONObject operationArea = jsonObject.optJSONObject("operation_area");
                        JSONArray pointsArray = (operationArea != null) ? operationArea.optJSONArray("points") : null;

                        if (pointsArray != null) {
                            wpcounter=0;
                            // Clear old waypoints before loading new ones
                            DefaultLayoutActivity.this.Waypoints.clear();

                            for (int i = 0; i < pointsArray.length(); i++) {
                                JSONObject pointObject = pointsArray.getJSONObject(i);

                                // "lat"/"lon", NOT "latitude"/"longitude". That is the
                                // shape the drawing map emits and the shape stored in
                                // mission.operation_area (see Ikaros lib/validations.ts,
                                // which says so explicitly). Reading the long names gave
                                // every vertex the 0.0 default, so a loaded mission was a
                                // polygon off the coast of Africa. Long names still
                                // accepted, so an older server keeps working.
                                double latitude = pointObject.optDouble("lat",
                                        pointObject.optDouble("latitude", 0.0));
                                double longitude = pointObject.optDouble("lon",
                                        pointObject.optDouble("longitude", 0.0));
                                double altitude = pointObject.optDouble("altitude", 20.0);
                                String heading = pointObject.optString("heading", "USING_WAYPOINT_HEADING");
                                String speed = pointObject.optString("speed", "3");
                                String option = pointObject.optString("option", "NO_ACTION");

                                // Store as a 2D array entry (object array for mixed types)
                                Object[] waypoint = new Object[]{
                                        latitude,
                                        longitude,
                                        altitude,
                                        Double.parseDouble(speed),
                                        heading,
                                        "NO_ACTION"
                                };

                                DefaultLayoutActivity.this.Waypoints.add(waypoint);
                            }

                            DefaultLayoutActivity.this.showToast("Mission loaded");
                        }
                    }

                } catch (JSONException e) {
                    Log.e("TAG Get Mission", "JSON parsing error", e);
                }
            }
        });
    }

    /* access modifiers changed from: private */
    public void postMissionStatus(String status) throws JSONException, IOException {
        JSONObject params = new JSONObject();
        String formattedDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(System.currentTimeMillis()));
        params.put("vehicle_id", vehicleId);
        params.put("timestamp", formattedDateTime);
        params.put("missionId", this.missionId);
        params.put(NotificationCompat.CATEGORY_STATUS, status);
        // Labels the server's event log. Absent, the transition is recorded as
        // the MAVLink middleware's, because a request arriving on that path is
        // the more conservative assumption.
        params.put("source", "android");
        new OkHttpClient().newCall(new Request.Builder().url(apiBase+"/api/android_app/vehicle/mission/mission_status").post(RequestBody.create(MediaType.parse("application/json"), params.toString())).addHeader("Content-Type", "application/json").addHeader("fbauthtoken", fbdevtoken).addHeader("userid", userId).build()).enqueue(new Callback() {
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                assert response.body() != null;
                Log.d("Post data to server", response.body().string());
            }
        });
    }

    private void postVehicleData() throws JSONException, IOException {

        String url = apiBase+"/api/android_app/vehicle/vehicle_data";

        JSONObject params = new JSONObject();

        long timestamp = System.currentTimeMillis();

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String formattedDateTime = sdf.format(new Date(timestamp));

        FlightControlState currentState = this.flightControlState.getValue();
//        Log.d("current lat", "lati" + currentState.getLatitude());
//        Log.d("current lon", "lon" + currentState.getLongitude());
//        Log.d("current battery", "bat" + currentState.getBattery());
//        Log.d("current heading", KMLConstants.HEADING + currentState.getHead());
//        Log.d("current altitude", "altitude" + currentState.getHeight());
//        Log.d("current speed", KMLConstants.SPEED + currentState.getSpeed());
        double heading = currentState.getHead();
        if (heading < 0) heading += 360;
        VehicleData vehicleData = new VehicleData(vehicleId, formattedDateTime, this.missionId, fbdevtoken,
                (int) currentState.getBattery(), wpcounter+1, heading, currentState.getSpeed(),
                new Point(currentState.getLatitude(), currentState.getLongitude(), currentState.getHeight()),
                // Null until the gimbal and the coordinate transform have both
                // reported. Gson drops null fields, so the frame still posts.
                currentGimbal(), currentLens());
        Gson gson = new Gson();
        String vehicleDataJson = gson.toJson(vehicleData);
        // new JSONObject(...), not the raw string. Passing the String made
        // JSONObject store it as a quoted, escaped value, so the server
        // received vehicle_data as JSON TEXT and rejected it with
        // "Validation failed: Expected object, received string".
        params.put("vehicle_data", new JSONObject(vehicleDataJson));
        OkHttpClient client = new OkHttpClient();
        MediaType mediaType = MediaType.parse("application/json");
        RequestBody body = RequestBody.create(mediaType, params.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("fbauthtoken",fbdevtoken)
                .addHeader("userid",userId)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                String ttoken = response.body().string();
                Log.d("Post data to server", ttoken);
                //showToast(ttoken);

            }
        });
    }
    public void wpenablefunc() {
        Log.d("Waypoint", "Enable Waypoint clicked - condition met");
        wpenable=true;
        startTakeoff();
        this.virtualStickVM.enableVirtualStick(new CommonCallbacks.CompletionCallback() {
            public void onSuccess() {
                Log.d("VirtualStick", "Enabled!");
            }

            public void onFailure(IDJIError idjiError) {
                Log.d("VirtualStick", "Failed to enable");
            }
        });
        Handler handler2 = new Handler();
        this.handlercontrol = handler2;
        handler2.postDelayed(this.secondRunnable, 1000);
    }

    public void wpdisablefunc() {
        onPictureCaptured();
        Runnable runnable2;
        wpenable=false;
        this.virtualStickVM.disableVirtualStick(new CommonCallbacks.CompletionCallback() {
            public void onSuccess() {
                Log.d("VirtualStick", "Disabled!");
            }

            public void onFailure(IDJIError idjiError) {
                Log.d("VirtualStick", "Failed to disable");
            }
        });
        Handler handler2 = this.handlercontrol;
        if (!(handler2 == null || (runnable2 = this.secondRunnable) == null)) {
            handler2.removeCallbacks(runnable2);
        }


//        FlightControlState currentState = this.flightControlState.getValue();
//        Log.d("current lat", "lati" + currentState.getLatitude());
//        Log.d("current lon", "lon" + currentState.getLongitude());
//        Log.d("current battery", "bat" + currentState.getBattery());
//        Log.d("current heading", KMLConstants.HEADING + currentState.getHead());
//        Log.d("current altitude", "altitude" + currentState.getHeight());
//        Log.d("current speed", KMLConstants.SPEED + currentState.getSpeed());
    }
    private void listenFlightControlState() {
        this.flightControlDisposable = Flowable.combineLatest(RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyHomeLocation), this).observeOn(AndroidSchedulers.mainThread()),
                RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyAircraftLocation), this).observeOn(AndroidSchedulers.mainThread()),
                RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyCompassHeading), this).observeOn(AndroidSchedulers.mainThread()),
                RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyAltitude), this).observeOn(AndroidSchedulers.mainThread()),
                RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity), this).observeOn(AndroidSchedulers.mainThread()),
                RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyBatteryPowerPercent), this).observeOn(AndroidSchedulers.mainThread()),
                RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyHeightAboveSeaLevel), this).observeOn(AndroidSchedulers.mainThread()),
                RxUtil.addListener(KeyTools.createKey(FlightControllerKey.KeyTakeoffLocationAltitude), this).observeOn(AndroidSchedulers.mainThread()),
                this::updatestate).subscribe();
    }

    /**
     * Keep the camera's pose current, for the telemetry Ikaros geolocates from.
     *
     * Three separate keys, because the absolute pointing direction is not one
     * of them: KeyGimbalAttitude's yaw is in the gimbal's own frame, and only
     * becomes a true-north azimuth once KeyImuCoordinateTran is added (the
     * uxsdk's own HSI does the same correction - HSIWidgetModel). Sending the
     * raw yaw would put every detection at a plausible but wrong bearing,
     * which is the kind of error that looks like a calibration problem for
     * weeks.
     *
     * Each is optional: a missing value leaves its field null and the frame is
     * still posted, rather than losing position and battery along with it.
     */
    private void listenCameraPose() {
        this.gimbalAttitudeDisposable = RxUtil.addListener(
                        KeyTools.createKey(GimbalKey.KeyGimbalAttitude), this)
                .subscribe(attitude -> {
                    if (attitude != null) {
                        this.gimbalYawRaw = attitude.getYaw();
                        this.gimbalPitch = attitude.getPitch();
                        this.gimbalRoll = attitude.getRoll();
                    }
                }, throwable -> LogUtils.e(TAG, "gimbal attitude listener failed", throwable));

        this.imuTranDisposable = RxUtil.addListener(
                        KeyTools.createKey(FlightControllerKey.KeyImuCoordinateTran), this)
                .subscribe(tran -> this.imuCoordinateTran = tran,
                        throwable -> LogUtils.e(TAG, "imu coordinate tran listener failed", throwable));

        // The zoom lens specifically. KeyTools.createCameraKey is what the
        // uxsdk's own zoom widget uses (FocalZoomWidgetViewModel); an aircraft
        // with no zoom lens simply never emits, leaving the ratio null and the
        // focal length at wide.
        this.zoomRatioDisposable = RxUtil.addListener(
                        KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios,
                                ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM), this)
                .subscribe(ratio -> this.zoomRatio = ratio,
                        throwable -> LogUtils.e(TAG, "zoom ratio listener failed", throwable));
    }

    /** Absolute gimbal pointing for the wire, or null if it is not yet known. */
    private CameraPose.Gimbal currentGimbal() {
        Double yawRaw = this.gimbalYawRaw;
        Double tran = this.imuCoordinateTran;
        Double pitch = this.gimbalPitch;
        Double roll = this.gimbalRoll;
        if (yawRaw == null || pitch == null || roll == null) {
            return null;
        }
        // Without the correction the yaw is not an azimuth. Refuse to guess:
        // an uncorrected value is worse than none, because the server cannot
        // tell it is wrong.
        if (tran == null) {
            return null;
        }
        double yaw = yawRaw + Math.toDegrees(tran);
        // Normalise to 0..360. The server accepts either convention and stores
        // what it is given, so send one consistently.
        yaw = ((yaw % 360.0) + 360.0) % 360.0;
        return new CameraPose.Gimbal(yaw, pitch, roll);
    }

    /** Focal length in use, or null when the lens is unknown. */
    private CameraPose.Lens currentLens() {
        Double ratio = this.zoomRatio;
        // No zoom lens, or nothing reported yet: the wide focal length is the
        // honest answer for a camera that cannot zoom.
        double effective = (ratio == null || ratio <= 0) ? this.wideFocalMm : this.wideFocalMm * ratio;
        if (!(effective > 0) || Double.isNaN(effective)) {
            return null;
        }
        return new CameraPose.Lens(effective);
    }

    private void listenGimbalControlState() {
        this.gimbalControlDisposable = RxUtil.addListener(KeyTools.createKey(GimbalKey.KeyGimbalAttitude), this)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(attitude -> {
                    if (attitude != null) {
                        this.gimbalControlState.setValue(new GimbalControlState(attitude.getRoll(),attitude.getPitch(),attitude.getYaw()));
                    }
                });

    }


    public FlightControlState  updatestate (LocationCoordinate2D homeLocation, LocationCoordinate2D aircraftLocation, Double compassheading, Double altitude, Velocity3D speed, Integer batteryPercent, HeightAboveSeaLevelMsg amslaltitude, Double takeoffaltitude)  {
        double speedabs = Math.sqrt((speed.getX() * speed.getX()) + (speed.getY()* speed.getY()));
        if (homeLocation == null) {
            return null;
        } else if (aircraftLocation == null) {
            return null;
        } else {
            FlightControlState state = new FlightControlState(aircraftLocation.getLongitude(), aircraftLocation.getLatitude(), (float) compassheading.doubleValue(),  altitude, (double) batteryPercent, speedabs, amslaltitude.getHeight(),takeoffaltitude);
            this.flightControlState.setValue(state);
            telemok = true;
            return state;
        }
    }

    public static float CalculateBearingAngle(double startLatitude, double startLongitude, double endLatitude, double endLongitude) {
        double Phi1 = Math.toRadians(startLatitude);
        double Phi2 = Math.toRadians(endLatitude);
        double DeltaLambda = Math.toRadians(endLongitude - startLongitude);
        return (float) Math.toDegrees(Math.atan2(Math.sin(DeltaLambda) * Math.cos(Phi2), (Math.cos(Phi1) * Math.sin(Phi2)) - ((Math.sin(Phi1) * Math.cos(Phi2)) * Math.cos(DeltaLambda))));
    }

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double lon12 = Math.toRadians(lon1);
        double lon22 = Math.toRadians(lon2);
        double lat12 = Math.toRadians(lat1);
        double lat22 = Math.toRadians(lat2);
        return Math.asin(Math.sqrt(Math.pow(Math.sin((lat22 - lat12) / 2.0d), 2.0d) + (Math.cos(lat12) * Math.cos(lat22) * Math.pow(Math.sin((lon22 - lon12) / 2.0d), 2.0d)))) * 2.0d * 6371.0d;
    }
    public void go2target() throws JSONException, IOException {
        FlightControlState currentState = this.flightControlState.getValue();
        if (wpenable && telemok) {
            double finalvelx;
            double finalvely;
            double v_attr_alt = 0.0d;
            double factor = 0.0d;

            double targetSpeed = 0;
            if (this.wpcounter < this.Waypoints.size()) {
                assert currentState != null;

                // Fetch current waypoint row
                Object[] waypoint = this.Waypoints.get(this.wpcounter);

                double targetLat = (Double) waypoint[0];
                double targetLon = (Double) waypoint[1];
                double targetAlt = (Double) waypoint[2];   // altitude
                targetSpeed = (Double) waypoint[3];
                String headingMode = (String) waypoint[4];
                String action = (String) waypoint[5];   // currently unused, but available

                double distanceToTarget = distance(
                        currentState.getLatitude(),
                        currentState.getLongitude(),
                        targetLat, targetLon
                );

                if (distanceToTarget > 0.005d) {
                    // Bearing difference
                    double bearing = CalculateBearingAngle(
                            currentState.getLatitude(), currentState.getLongitude(),
                            targetLat, targetLon
                    );
                    double headingDiff = currentState.getHead() - bearing;
                    double absHeadingDiff = Math.abs(headingDiff);

                    // Normalize heading difference to [-180, 180]
                    if (headingDiff < 0.0d && absHeadingDiff > 180.0d) {
                        headingDiff += 360.0d;
                    }
                    if (headingDiff > 180.0d) {
                        headingDiff -= 360.0d;
                    }

                    // Altitude adjustment
                    double altError = targetAlt - currentState.getHeight();
                    double altFactor = altError / Math.sqrt((altError * altError) + 25.0d);

                    // Forward direction vector based on yaw
                    double yawRad = Math.toRadians(headingDiff);
                    double fwdX = Math.cos(yawRad);
                    double fwdY = Math.sin(yawRad);

                    // Rotation (yaw rate)
                    double rotation = 0.0d;
                    if ("USING_WAYPOINT_HEADING".equals(headingMode)) {
                        Log.d("VirtualStick", "headingdiff " + headingDiff + " bearing " + bearing + " current head " + currentState.getHead());
                        if (!this.lockedyaw) {
                            double rotationCmd = headingDiff / Math.sqrt((headingDiff * headingDiff) + 10000.0d) * -1.0d;

                            if (Math.abs(headingDiff) <= 1.7d) {
                                this.lockedyaw = true;
                                rotation = 0.0d;
                            } else {
                                rotation = rotationCmd;
                            }
                        }
                        if (Math.abs(headingDiff) > 4.0d) {
                            this.lockedyaw = false;
                        }

                        if (Math.abs(headingDiff) >= 15.0d || altError>10) {
                            // Prioritize rotation correction
                            finalvelx = 0.0d;
                            finalvely = 0.0d;
                            v_attr_alt = altFactor;
                            factor = rotation;
                        } else {
                            // Move forward while adjusting yaw
                            finalvelx = fwdX;
                            finalvely = fwdY;
                            v_attr_alt = altFactor;
                            factor = rotation;
                        }
                    } else {
                        // Ignore waypoint heading, just fly forward
                        finalvelx = fwdX;
                        finalvely = fwdY;
                        v_attr_alt = altFactor;
                        factor = 0;
                    }
                } else {
                    // Close enough → stop
                    finalvelx = 0.0d;
                    finalvely = 0.0d;
                }

                // Slow down if within 0.02° (~2m)
                if (distanceToTarget < 0.02d) {
                    finalvelx /= 2.0d;
                    finalvely /= 2.0d;
                }

                // Waypoint reached → move to next row
                if (distanceToTarget <= 0.005d) {
                    this.wpcounter++;
                    if(this.wpcounter == this.Waypoints.size()) {
                        DefaultLayoutActivity.this.postMissionStatus("Completed");
                        startReturnToHome();

                    }
                }
            } else {
                // Out of waypoints
                finalvelx = 0.0d;
                finalvely = 0.0d;

            }
            double speedMod = 29.5 * targetSpeed;
            int leftX = (int) (200.0 * factor);
            int leftY = (int) (70.0 * v_attr_alt);
            int rightX = (int) (speedMod * finalvelx);
            int rightY = (int) (speedMod * finalvely * -1.0d);

            // Send stick commands
            this.virtualStickVM.setLeftPosition(leftX, leftY);
            this.virtualStickVM.setRightPosition(rightY, rightX);
        }
    }

    private void postHeartbeat() throws JSONException {
        FlightControlState state = flightControlState.getValue();
        if (state == null) return;

        JSONObject point = new JSONObject();
        point.put("lat", state.getLatitude());
        point.put("lon", state.getLongitude());
        point.put("alt", state.getHeight());

        JSONObject body = new JSONObject();
        body.put("vehicleId", Integer.parseInt(vehicleId));
        body.put("point", point);
        double hbHeading = state.getHead();
        if (hbHeading < 0) hbHeading += 360;
        body.put("battery", (int) state.getBattery());
        body.put("heading", hbHeading);
        body.put("speed", state.getSpeed());

        OkHttpClient client = new OkHttpClient();
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), body.toString());
        Request request = new Request.Builder()
                .url(apiBase + "/api/telemetry")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                // This endpoint requires the service token too; without it
                // every heartbeat was answered with 401.
                .addHeader("fbauthtoken", fbdevtoken)
                .addHeader("userid", userId)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("Heartbeat", "Failed to post heartbeat", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("Heartbeat", "Heartbeat error: " + response.code());
                } else {
                    Log.d("Heartbeat", "Heartbeat OK");
                }
                response.close();
            }
        });
    }

    private void postDJITelemetry() {
        String url = "http://" + wardenIP + ":5100/update"; // adjust port if Flask runs elsewhere

        // Read flight and gimbal state
        FlightControlState state = flightControlState.getValue();
        GimbalControlState gimbalState = gimbalControlState.getValue();

        if (state == null || gimbalState == null) {
            Log.e("Telemetry", "Missing flight or gimbal state!");
            return;
        }

        // Prepare telemetry values
        double lat = state.getLatitude();
        double lon = state.getLongitude();
        double alt = state.getHeight();
        double pitch = gimbalState.getPitch();
        double roll = gimbalState.getRoll();
        double yaw = gimbalState.getYaw();
        double absalt = GpsUtils.egm96Altitude(state.getTakeoffheight() + state.getHeight(), lat, lon);
        double[] target = TargetLocator.computeTargetGeodeticCoords(
                lat,
                lon,
                alt,
                roll,
                pitch,
                yaw
        );

        double targetLat = target[0];
        double targetLon = target[1];
        double groundDistance = target[2];

        // Build the JSON expected by Flask
        JSONObject telemetry = new JSONObject();
        JSONObject payload = new JSONObject();
        try {
            telemetry.put("lat", lat);
            telemetry.put("lon", lon);
            telemetry.put("alt", alt);
            telemetry.put("absalt", absalt);
            telemetry.put("roll", roll);
            telemetry.put("pitch", pitch);
            telemetry.put("yaw", yaw);
            telemetry.put("targetLat", targetLat);
            telemetry.put("targetLon", targetLon);
            telemetry.put("groundDistance", groundDistance);
            payload.put("dji3t", telemetry);
        } catch (JSONException e) {
            Log.e("Telemetry", "Error creating JSON", e);
            return;
        }

        OkHttpClient client = new OkHttpClient();
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(mediaType, payload.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                Log.e("Telemetry", "Failed to post telemetry", e);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("Telemetry", "Server error: " + response.code());
                } else {
                    Log.d("Telemetry", "Telemetry posted successfully!");
                }
                response.close();
            }
        });
    }


    private void onPictureCaptured() {

        // Get telemetry values
        FlightControlState state = flightControlState.getValue();
        GimbalControlState gimbalState = gimbalControlState.getValue();

        if (state == null || gimbalState == null) {
            Log.e("Telemetry", "Missing flight or gimbal state!");
            return;
        }

        double lat   = state.getLatitude();
        double lon   = state.getLongitude();
        double alt   = state.getHeight();
        double amslalt = state.getAmslheight();
        double takeoffalt = state.getTakeoffheight();
        double pitch = gimbalState.getPitch();
        double roll  = gimbalState.getRoll();
        double yaw   = gimbalState.getYaw();

        // Prepare text content
        String content = String.format(
                "Latitude: %.7f\nLongitude: %.7f\nAltitude: %.2f\nAMSL_Altitude: %.2f\nTakeoff_Altitude: %.2f\nPitch: %.2f\nRoll: %.2f\nYaw: %.2f\n",
                lat, lon, alt, amslalt,takeoffalt, pitch, roll, yaw
        );

        // Timestamped filename
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "telemetry_" + timestamp + ".txt";

        // Save to Documents directory
        File docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (docsDir != null && !docsDir.exists()) {
            docsDir.mkdirs();
        }
        File txtFile = new File(docsDir, fileName);

        try (FileWriter writer = new FileWriter(txtFile)) {
            writer.write(content);
            Log.d("Telemetry", "Wrote telemetry file: " + txtFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e("Telemetry", "Failed writing TXT file", e);
        }
    }
    public void startTakeoff() {
        KeyManager.getInstance().performAction(
                KeyTools.createKey(FlightControllerKey.KeyStartTakeoff),
                null, // no params
                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                    @Override
                    public void onSuccess(EmptyMsg emptyMsg) {
                        Log.d("DJI", "Takeoff command sent successfully");

                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        Log.e("DJI", "Takeoff failed: " + error.description());
                    }
                }
        );
    }
    public void getProductUUIDAndShowToast() {
        KeyManager.getInstance().getValue(
                KeyTools.createKey(ProductKey.KeyProductUUID),
                new CommonCallbacks.CompletionCallbackWithParam<String>() {
                    @Override
                    public void onSuccess(String uuid) {
                        Log.d("DJI", "Product UUID: " + uuid);
                        showToast(uuid);
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        Log.e("DJI", "Failed to get Product UUID: " + error.description());
                    }
                }
        );
    }

    public void startReturnToHome() {
        KeyManager.getInstance().setValue(
                KeyTools.createKey(FlightControllerKey.KeyGoHomeHeight),
                30,   // in meters
                new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onSuccess() {
                        Log.d("DJI", "RTH altitude set to 30m");

                        // 2. Now issue RTH command
                        KeyManager.getInstance().performAction(
                                KeyTools.createKey(FlightControllerKey.KeyStartGoHome),
                                null,
                                new CommonCallbacks.CompletionCallbackWithParam<EmptyMsg>() {
                                    @Override
                                    public void onSuccess(EmptyMsg emptyMsg) {
                                        Log.d("DJI", "Return-To-Home initiated");
                                    }

                                    @Override
                                    public void onFailure(@NonNull IDJIError error) {
                                        Log.e("DJI", "RTH failed: " + error.description());
                                    }
                                }
                        );
                    }

                    @Override
                    public void onFailure(@NonNull IDJIError error) {
                        Log.e("DJI", "Failed to set RTH altitude: " + error.description());
                    }
                }
        );
    }


    // ── Mission and segment controls ──────────────────────────────────────
    //
    // Three presses, in the order a sortie actually happens: Start when the
    // aircraft is over the area, Segment while something is worth looking at,
    // Stop on the way home.

    /**
     * Start the loaded mission and begin publishing.
     *
     * The mission has to be loaded already - get_mission?attended=1 puts it
     * there without starting it, which is the whole point of the attended
     * read. Streaming starts with the status change rather than before it, so
     * the detector the server starts has a stream to attach to and the
     * recording begins where the mission does.
     */
    public void startMission() {
        if (this.missionId == null || this.missionId.isEmpty() || this.missionId.equals("0")) {
            showToast("No mission for this aircraft");
            return;
        }
        if (this.missionStarted) {
            showToast("Mission already started");
            return;
        }
        try {
            postMissionStatus("InProgress");
            this.missionStarted = true;
            startLiveStream();
            showToast("Mission started");
        } catch (JSONException | IOException e) {
            Log.e("Mission", "start failed", e);
            showToast("Could not start the mission");
        }
    }

    /**
     * End the mission and stop publishing.
     *
     * Completed, not Aborted: this is the ordinary end of a flight. The server
     * reaps the detector and closes any segment still open, so a forgotten
     * Segment press cannot leave a span running to the end of time.
     *
     * The status goes first and the stream stops second. The other order would
     * drop the publisher while the server still believed the mission live,
     * which reads downstream as a stream that failed rather than a flight that
     * finished.
     */
    public void stopMission() {
        if (this.missionId == null || this.missionId.isEmpty() || this.missionId.equals("0")) {
            showToast("No mission to stop");
            return;
        }
        try {
            postMissionStatus("Completed");
            this.missionStarted = false;
            this.segmentOpen = false;
            stopLiveStream();
            showToast("Mission completed");
        } catch (JSONException | IOException e) {
            Log.e("Mission", "stop failed", e);
            showToast("Could not complete the mission");
        }
    }

    /** Open a marked segment if none is open, close it if one is. */
    public void toggleSegment() {
        if (!this.missionStarted) {
            showToast("Start the mission first");
            return;
        }
        postSegment(this.segmentOpen ? "stop" : "start");
    }

    /**
     * Open or close a marked span of this flight.
     *
     * Segments are what the evidence report is built from: the server scopes
     * its analysis to them, so what is marked here is what gets looked at, and
     * the transit and climb are left out rather than diluting it.
     *
     * client_ts is this tablet's clock. The server stamps its own and keeps
     * both - the difference is reaction time plus streaming latency, and
     * recording it means that offset can be measured later instead of guessed
     * at now. The server's clock is the one that counts.
     *
     * Idempotent on the server in both directions, so a double press or a
     * retry over a bad link is harmless.
     */
    private void postSegment(final String action) {
        JSONObject params = new JSONObject();
        try {
            params.put("missionId", this.missionId);
            params.put("vehicle_id", vehicleId);
            params.put("action", action);
            // ISO-8601 UTC. The server discards a clock more than an hour out
            // rather than storing something misleading.
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            params.put("client_ts", iso.format(new Date(System.currentTimeMillis())));
        } catch (JSONException e) {
            Log.e("Segment", "could not build request", e);
            return;
        }

        Request request = new Request.Builder()
                .url(apiBase + "/api/android_app/vehicle/mission/segment")
                .post(RequestBody.create(MediaType.parse("application/json"), params.toString()))
                .addHeader("Content-Type", "application/json")
                .addHeader("fbauthtoken", fbdevtoken)
                .addHeader("userid", userId)
                .build();

        new OkHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e("Segment", action + " failed", e);
                showToast("Segment " + action + " failed");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                // Only believe the flag on a response the server accepted.
                // Flipping it optimistically would leave the button lying
                // about what the recording actually has marked.
                if (response.isSuccessful()) {
                    DefaultLayoutActivity.this.segmentOpen = "start".equals(action);
                    showToast("start".equals(action) ? "Segment started" : "Segment ended");
                    updateSegmentButton();
                } else {
                    Log.e("Segment", action + " rejected: " + response.code());
                    showToast("Segment " + action + " rejected");
                }
                response.close();
            }
        });
    }

    /** Label the segment button with what the next press will do. */
    private void updateSegmentButton() {
        final boolean open = this.segmentOpen;
        runOnUiThread(() -> {
            if (this.mMissionSegment != null) {
                this.mMissionSegment.setText(open
                        ? R.string.uxsdk_segment_stop
                        : R.string.uxsdk_segment_start);
            }
        });
    }
}
