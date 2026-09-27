package dji.sampleV5.aircraft.data


/**
 * @author feel.feng
 * @time 2022/03/19 6:30 下午
 * @description: 飞机位置信息，坐标，朝向
 */
data class FlightControlState(
    var longitude : Double = 0.0
    , var latitude: Double = 0.0
    , val head : Float = 0.0f
    , val height : Double = 0.0
    , var battery : Double = 0.0
    , val speed :Double = 0.0
    , var amslheight : Double = 0.0
    , var takeoffheight : Double = 0.0
)