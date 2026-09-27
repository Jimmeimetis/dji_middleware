package dji.sampleV5.aircraft

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dji.sampleV5.aircraft.databinding.ActivityMainBinding
import dji.sampleV5.aircraft.models.MSDKInfoVm
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels
import dji.sampleV5.aircraft.util.Helper
import dji.sampleV5.aircraft.util.ToastUtils
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.PermissionUtil
import dji.v5.utils.common.StringUtils
import io.reactivex.rxjava3.disposables.CompositeDisposable

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/2/10
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
abstract class DJIMainActivity : AppCompatActivity() {

    companion object {
        /** Bump this whenever the defaults below change, to re-push them to
         *  devices that have already saved the old ones. */
        private const val CONFIG_VERSION = 2
        private const val KEY_CONFIG_VERSION = "configVersion"

        /** Must match a vehicle id in the Ikaros database: telemetry is filed
         *  against it and the video player subscribes to live/<vehicleId>. */
        private const val DEFAULT_VEHICLE_ID = "3"

        /** The deployment's INTERNAL_API_KEY - generated per environment.
         *  Comes from IKAROS_TOKEN in secrets.properties. */
        private const val DEFAULT_FBDEV_TOKEN = BuildConfig.IKAROS_TOKEN

        /** Attribution only; the token above is what authenticates. */
        private const val DEFAULT_USER_ID = "1"

        /** Ikaros API, reached over https on 443. */
        private const val DEFAULT_IKAROS_HOST = "ikaros-dev01.autonoma-solutions.eu"

        /** RTMP ingest - a different hostname from the API on purpose: the
         *  API's load balancer only speaks HTTP and cannot carry RTMP. */
        private const val DEFAULT_RTMP_HOST = "rtmp-dev01.autonoma-solutions.eu"
        private const val DEFAULT_RTMP_USER = "ikaros"

        /** Comes from RTMP_PASS in secrets.properties. */
        private const val DEFAULT_RTMP_PASS = BuildConfig.RTMP_PASS
    }

    val tag: String = LogUtils.getTag(this)
    private val permissionArray = arrayListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.KILL_BACKGROUND_PROCESSES,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    init {
        permissionArray.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                add(Manifest.permission.READ_MEDIA_IMAGES)
//                add(Manifest.permission.READ_MEDIA_VIDEO)
//                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private val msdkInfoVm: MSDKInfoVm by viewModels()
    private val msdkManagerVM: MSDKManagerVM by globalViewModels()
    private lateinit var binding: ActivityMainBinding
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val disposable = CompositeDisposable()

    abstract fun prepareUxActivity()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 有一些手机从系统桌面进入的时候可能会重启main类型的activity
        // 需要校验这种情况，业界标准做法，基本所有app都需要这个
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN == intent.action) {

            finish()
            return

        }

        window.decorView.apply {
            systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        initMSDKInfoView()
        observeSDKManager()
        checkPermissionAndRequest()
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        // One-time migration to the Ikaros dev01 settings.
        //
        // A SAVED preference beats the hardcoded default in
        // DefaultLayoutActivity, so any device that has ever pressed Save
        // would keep talking to the old server for ever - changing the
        // defaults below alone would not reach it. Bump CONFIG_VERSION
        // whenever these change and the stale values are rewritten once.
        if (prefs.getInt(KEY_CONFIG_VERSION, 0) < CONFIG_VERSION) {
            prefs.edit()
                .putString("vehicleId", DEFAULT_VEHICLE_ID)
                .putString("fbdevtoken", DEFAULT_FBDEV_TOKEN)
                .putString("userId", DEFAULT_USER_ID)
                .putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
                .apply()
            ToastUtils.showToast("Settings updated for Ikaros dev01")
        }

// Pre-fill if saved previously
        binding.etVehicleId.setText(prefs.getString("vehicleId", DEFAULT_VEHICLE_ID))
        binding.etFbdevToken.setText(prefs.getString("fbdevtoken", DEFAULT_FBDEV_TOKEN))
        binding.etUserId.setText(prefs.getString("userId", DEFAULT_USER_ID))
        binding.etIkarosHost.setText(prefs.getString("ikarosHost", DEFAULT_IKAROS_HOST))
        binding.etRtmpHost.setText(prefs.getString("rtmpHost", DEFAULT_RTMP_HOST))
        binding.etRtmpUser.setText(prefs.getString("rtmpUser", DEFAULT_RTMP_USER))
        binding.etRtmpPass.setText(prefs.getString("rtmpPass", DEFAULT_RTMP_PASS))

        binding.btnSave.setOnClickListener {
            prefs.edit()
                .putString("vehicleId", binding.etVehicleId.text.toString().trim())
                .putString("fbdevtoken", binding.etFbdevToken.text.toString().trim())
                .putString("userId", binding.etUserId.text.toString().trim())
                .putString("ikarosHost", binding.etIkarosHost.text.toString().trim())
                .putString("rtmpHost", binding.etRtmpHost.text.toString().trim())
                .putString("rtmpUser", binding.etRtmpUser.text.toString().trim())
                .putString("rtmpPass", binding.etRtmpPass.text.toString().trim())
                .apply()

            ToastUtils.showToast("Values saved")

        }
    }

    @SuppressLint("SetTextI18n")
    private fun initMSDKInfoView() {
        msdkInfoVm.msdkInfo.observe(this) {
            binding.textViewProductName.text = StringUtils.getResStr(R.string.product_name, it.productType.name)
            binding.textViewProductUuid.text = StringUtils.getResStr(R.string.product_uuid, it.productUUID)
        }
    }

    private fun observeSDKManager() {
        msdkManagerVM.lvRegisterState.observe(this) { resultPair ->
            val statusText: String?
            if (resultPair.first) {
                ToastUtils.showToast("Register Success")
                statusText = StringUtils.getResStr(this, R.string.registered)
                msdkInfoVm.initListener()
                handler.postDelayed({
                    prepareUxActivity()
                }, 5000)
            } else {
                showToast("Register Failure: ${resultPair.second}")
                statusText = StringUtils.getResStr(this, R.string.unregistered)
            }
            binding.textViewRegistered.text = StringUtils.getResStr(R.string.registration_status, statusText)
        }

        msdkManagerVM.lvProductConnectionState.observe(this) { resultPair ->
            showToast("Product: ${resultPair.second} ,ConnectionState:  ${resultPair.first}")
        }

        msdkManagerVM.lvProductChanges.observe(this) { productId ->
            showToast("Product: $productId Changed")
        }

        msdkManagerVM.lvInitProcess.observe(this) { processPair ->
            showToast("Init Process event: ${processPair.first.name}")
        }

        msdkManagerVM.lvDBDownloadProgress.observe(this) { resultPair ->
            showToast("Database Download Progress current: ${resultPair.first}, total: ${resultPair.second}")
        }
    }

    private fun showToast(content: String) {
        ToastUtils.showToast(content)

    }


    fun <T> enableDefaultLayout(cl: Class<T>) {
        enableShowCaseButton(binding.defaultLayoutButton, cl)
    }

    private fun <T> enableShowCaseButton(view: View, cl: Class<T>) {
        view.isEnabled = true
        view.setOnClickListener {
            Intent(this, cl).also {
                startActivity(it)
            }
        }
    }

    private fun checkPermissionAndRequest() {
        if (!checkPermission()) {
            requestPermission()
        }
    }

    private fun checkPermission(): Boolean {
        for (i in permissionArray.indices) {
            if (!PermissionUtil.isPermissionGranted(this, permissionArray[i])) {
                return false
            }
        }
        return true
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result?.entries?.forEach {
            if (!it.value) {
                requestPermission()
                return@forEach
            }
        }
    }

    private fun requestPermission() {
        requestPermissionLauncher.launch(permissionArray.toArray(arrayOf()))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        disposable.dispose()
    }
}