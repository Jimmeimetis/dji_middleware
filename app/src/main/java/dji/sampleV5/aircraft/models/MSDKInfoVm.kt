package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData
import dji.sampleV5.aircraft.data.DEFAULT_STR
import dji.sampleV5.aircraft.data.MSDKInfo
import dji.sampleV5.aircraft.data.NO_NETWORK_STR
import dji.sampleV5.aircraft.data.ONLINE_STR
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.ProductKey
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.areacode.AreaCodeChangeListener
import dji.v5.manager.areacode.AreaCodeManager
import dji.v5.manager.ldm.LDMManager
import dji.v5.network.DJINetworkManager
import dji.v5.network.IDJINetworkStatusListener
import dji.v5.utils.common.LogUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ViewModel for MSDKInfo UI
 * Handles: product type, firmware, area code, network, LDM, UUID
 */
class MSDKInfoVm : DJIViewModel() {

    private val tag: String = LogUtils.getTag(this)

    val msdkInfo = MutableLiveData<MSDKInfo>()
    val mainTitle = MutableLiveData<String>()

    private val isInited = AtomicBoolean(false)
    private val msdkInfoModel: MSDKInfoModel = MSDKInfoModel()

    private var areaCodeChangeListener: AreaCodeChangeListener
    private var netWorkStatusListener: IDJINetworkStatusListener

    init {
        // Initial model setup
        msdkInfo.value = MSDKInfo(msdkInfoModel.getSDKVersion()).apply {
            buildVer = msdkInfoModel.getBuildVersion()
            isDebug = msdkInfoModel.isDebug()
            packageProductCategory = msdkInfoModel.getPackageProductCategory()
            isLDMEnabled = LDMManager.getInstance().isLDMEnabled.toString()
            isLDMLicenseLoaded = LDMManager.getInstance().isLDMLicenseLoaded.toString()
            coreInfo = msdkInfoModel.getCoreInfo()
            productUUID = DEFAULT_STR      // initialize UUID
        }

        areaCodeChangeListener = AreaCodeChangeListener { _, changed ->
            LogUtils.i(tag, "areaCodeData", changed)
            msdkInfo.value?.countryCode = changed?.areaCode ?: DEFAULT_STR
            refreshMSDKInfo()
        }

        netWorkStatusListener = IDJINetworkStatusListener {
            LogUtils.i(tag, "isNetworkAvailable", it)
            updateNetworkInfo(it)
            refreshMSDKInfo()
        }

        refreshMSDKInfo()
    }

    override fun onCleared() {
        removeListener()
    }

    fun refreshMSDKInfo() {
        msdkInfo.postValue(msdkInfo.value)
    }

    /**
     * Called after SDK register success
     */
    fun initListener() {
        if (!SDKManager.getInstance().isRegistered) return
        if (isInited.getAndSet(true)) return

        // When FC connection changes → update firmware + UUID
        FlightControllerKey.KeyConnection.create().listen(this) {
            LogUtils.i(tag, "KeyConnection: $it")
            updateFirmwareVersion()
            updateProductUUID()       // ← ADD THIS LINE
        }

        // Product type update
        ProductKey.KeyProductType.create().listen(this) {
            LogUtils.i(tag, "KeyProductType: $it")
            it?.let { value ->
                msdkInfo.value?.productType = value
                refreshMSDKInfo()
            }
        }

        AreaCodeManager.getInstance().addAreaCodeChangeListener(areaCodeChangeListener)
        DJINetworkManager.getInstance().addNetworkStatusListener(netWorkStatusListener)
    }

    private fun removeListener() {
        KeyManager.getInstance().cancelListen(this)
        AreaCodeManager.getInstance().removeAreaCodeChangeListener(areaCodeChangeListener)
        DJINetworkManager.getInstance().removeNetworkStatusListener(netWorkStatusListener)
    }

    private fun updateNetworkInfo(isAvailable: Boolean) {
        msdkInfo.value?.networkInfo = if (isAvailable) ONLINE_STR else NO_NETWORK_STR
    }

    private fun updateFirmwareVersion() {
        ProductKey.KeyFirmwareVersion.create().get({
            LogUtils.i(tag, "Firmware onSuccess: $it")
            msdkInfo.value?.firmwareVer = it ?: DEFAULT_STR
            refreshMSDKInfo()
        }) {
            LogUtils.i(tag, "Firmware onFailure: $it")
            msdkInfo.value?.firmwareVer = DEFAULT_STR
            refreshMSDKInfo()
        }
    }

    /**
     * NEW: Retrieve ProductUUID and push into LiveData
     */
    fun updateProductUUID() {
        ProductKey.KeyProductUUID.create().get({ uuid ->
            LogUtils.i(tag, "ProductUUID onSuccess: $uuid")
            msdkInfo.value?.productUUID = uuid ?: DEFAULT_STR
            refreshMSDKInfo()
        }) { error ->
            LogUtils.e(tag, "ProductUUID onFailure: $error")
            msdkInfo.value?.productUUID = DEFAULT_STR
            refreshMSDKInfo()
        }
    }

    fun updateLDMStatus() {
        msdkInfo.value?.isLDMEnabled = LDMManager.getInstance().isLDMEnabled.toString()
        msdkInfo.value?.isLDMLicenseLoaded = LDMManager.getInstance().isLDMLicenseLoaded.toString()
        refreshMSDKInfo()
    }
}
