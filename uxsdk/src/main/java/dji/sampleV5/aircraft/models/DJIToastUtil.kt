package dji.sampleV5.aircraft.models

import androidx.lifecycle.MutableLiveData

/**
 * Description :djiToastLD的存储和提供工具类，用于向其他ViewModel提供djiToastLD
 *
 * @author: Byte.Cai
 * date : 2022/6/16
 *
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
object DJIToastUtil {
    var dJIToastLD: MutableLiveData<DJIToastResult>? = null
}


class DJIToastResult(var isSuccess: Boolean, var msg: String? = null) {

    companion object {
        fun success(msg: String? = null): DJIToastResult {
            return DJIToastResult(true, "success ${msg ?: ""}")
        }

        fun failed(msg: String): DJIToastResult {
            return DJIToastResult(false, msg)
        }
    }
}



