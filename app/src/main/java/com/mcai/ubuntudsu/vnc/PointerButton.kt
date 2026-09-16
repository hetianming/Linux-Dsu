package com.mcai.ubuntudsu.vnc

/** RFB pointer button bit masks. */
enum class PointerButton(val bitMask: Int) {
    None(0),
    Left(1),
    Middle(2),
    Right(4),
    WheelUp(8),
    WheelDown(16),
    WheelLeft(32),
    WheelRight(64),
}
