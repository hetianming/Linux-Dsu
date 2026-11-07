package com.mcai.ubuntudsu.core

import android.view.KeyEvent

/**
 * Maps Android KeyEvent codes to Linux evdev scan codes.
 *
 * Reference: TerminalApp's BTN_KEY_CODE_MAP and packages/modules/Virtualization/vm_payload/src/input.rs
 */
object KeyCodeMapper {

    /**
     * Maps Android KeyEvent.KEYCODE_* to Linux evdev KEY_* scan code.
     * Returns null if the key is not supported.
     */
    fun androidToEvdev(keyCode: Int): Int? = KEYCODE_MAP[keyCode]

    /**
     * Some Android keys require Shift to be synthesized (e.g., @ = Shift+2).
     * Returns the base key and whether Shift should be held.
     */
    fun needsShiftSynthesis(keyCode: Int): Pair<Int, Boolean>? {
        return when (keyCode) {
            KeyEvent.KEYCODE_AT -> Pair(KeyEvent.KEYCODE_2, true)         // @
            KeyEvent.KEYCODE_POUND -> Pair(KeyEvent.KEYCODE_3, true)      // #
            KeyEvent.KEYCODE_STAR -> Pair(KeyEvent.KEYCODE_8, true)       // *
            KeyEvent.KEYCODE_PLUS -> Pair(KeyEvent.KEYCODE_EQUALS, true)  // +
            else -> null
        }
    }

    // Linux evdev KEY_* codes (from linux/input-event-codes.h)
    const val KEY_ESC = 1
    const val KEY_1 = 2
    const val KEY_2 = 3
    const val KEY_3 = 4
    const val KEY_4 = 5
    const val KEY_5 = 6
    const val KEY_6 = 7
    const val KEY_7 = 8
    const val KEY_8 = 9
    const val KEY_9 = 10
    const val KEY_0 = 11
    const val KEY_MINUS = 12
    const val KEY_EQUAL = 13
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_Q = 16
    const val KEY_W = 17
    const val KEY_E = 18
    const val KEY_R = 19
    const val KEY_T = 20
    const val KEY_Y = 21
    const val KEY_U = 22
    const val KEY_I = 23
    const val KEY_O = 24
    const val KEY_P = 25
    const val KEY_LEFTBRACE = 26
    const val KEY_RIGHTBRACE = 27
    const val KEY_ENTER = 28
    const val KEY_LEFTCTRL = 29
    const val KEY_A = 30
    const val KEY_S = 31
    const val KEY_D = 32
    const val KEY_F = 33
    const val KEY_G = 34
    const val KEY_H = 35
    const val KEY_J = 36
    const val KEY_K = 37
    const val KEY_L = 38
    const val KEY_SEMICOLON = 39
    const val KEY_APOSTROPHE = 40
    const val KEY_GRAVE = 41
    const val KEY_LEFTSHIFT = 42
    const val KEY_BACKSLASH = 43
    const val KEY_Z = 44
    const val KEY_X = 45
    const val KEY_C = 46
    const val KEY_V = 47
    const val KEY_B = 48
    const val KEY_N = 49
    const val KEY_M = 50
    const val KEY_COMMA = 51
    const val KEY_DOT = 52
    const val KEY_SLASH = 53
    const val KEY_RIGHTSHIFT = 54
    const val KEY_KPASTERISK = 55
    const val KEY_LEFTALT = 56
    const val KEY_SPACE = 57
    const val KEY_CAPSLOCK = 58
    const val KEY_F1 = 59
    const val KEY_F2 = 60
    const val KEY_F3 = 61
    const val KEY_F4 = 62
    const val KEY_F5 = 63
    const val KEY_F6 = 64
    const val KEY_F7 = 65
    const val KEY_F8 = 66
    const val KEY_F9 = 67
    const val KEY_F10 = 68
    const val KEY_NUMLOCK = 69
    const val KEY_SCROLLLOCK = 70
    const val KEY_KP7 = 71
    const val KEY_KP8 = 72
    const val KEY_KP9 = 73
    const val KEY_KPMINUS = 74
    const val KEY_KP4 = 75
    const val KEY_KP5 = 76
    const val KEY_KP6 = 77
    const val KEY_KPPLUS = 78
    const val KEY_KP1 = 79
    const val KEY_KP2 = 80
    const val KEY_KP3 = 81
    const val KEY_KP0 = 82
    const val KEY_KPDOT = 83
    const val KEY_F11 = 87
    const val KEY_F12 = 88
    const val KEY_KPENTER = 96
    const val KEY_RIGHTCTRL = 97
    const val KEY_KPSLASH = 98
    const val KEY_SYSRQ = 99
    const val KEY_RIGHTALT = 100
    const val KEY_HOME = 102
    const val KEY_UP = 103
    const val KEY_PAGEUP = 104
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_END = 107
    const val KEY_DOWN = 108
    const val KEY_PAGEDOWN = 109
    const val KEY_INSERT = 110
    const val KEY_DELETE = 111
    const val KEY_MUTE = 113
    const val KEY_VOLUMEDOWN = 114
    const val KEY_VOLUMEUP = 115
    const val KEY_POWER = 116
    const val KEY_KPEQUAL = 117
    const val KEY_PAUSE = 119
    const val KEY_LEFTMETA = 125
    const val KEY_RIGHTMETA = 126
    const val KEY_COMPOSE = 127

    private val KEYCODE_MAP = mapOf(
        // Letters
        KeyEvent.KEYCODE_A to KEY_A,
        KeyEvent.KEYCODE_B to KEY_B,
        KeyEvent.KEYCODE_C to KEY_C,
        KeyEvent.KEYCODE_D to KEY_D,
        KeyEvent.KEYCODE_E to KEY_E,
        KeyEvent.KEYCODE_F to KEY_F,
        KeyEvent.KEYCODE_G to KEY_G,
        KeyEvent.KEYCODE_H to KEY_H,
        KeyEvent.KEYCODE_I to KEY_I,
        KeyEvent.KEYCODE_J to KEY_J,
        KeyEvent.KEYCODE_K to KEY_K,
        KeyEvent.KEYCODE_L to KEY_L,
        KeyEvent.KEYCODE_M to KEY_M,
        KeyEvent.KEYCODE_N to KEY_N,
        KeyEvent.KEYCODE_O to KEY_O,
        KeyEvent.KEYCODE_P to KEY_P,
        KeyEvent.KEYCODE_Q to KEY_Q,
        KeyEvent.KEYCODE_R to KEY_R,
        KeyEvent.KEYCODE_S to KEY_S,
        KeyEvent.KEYCODE_T to KEY_T,
        KeyEvent.KEYCODE_U to KEY_U,
        KeyEvent.KEYCODE_V to KEY_V,
        KeyEvent.KEYCODE_W to KEY_W,
        KeyEvent.KEYCODE_X to KEY_X,
        KeyEvent.KEYCODE_Y to KEY_Y,
        KeyEvent.KEYCODE_Z to KEY_Z,

        // Numbers
        KeyEvent.KEYCODE_0 to KEY_0,
        KeyEvent.KEYCODE_1 to KEY_1,
        KeyEvent.KEYCODE_2 to KEY_2,
        KeyEvent.KEYCODE_3 to KEY_3,
        KeyEvent.KEYCODE_4 to KEY_4,
        KeyEvent.KEYCODE_5 to KEY_5,
        KeyEvent.KEYCODE_6 to KEY_6,
        KeyEvent.KEYCODE_7 to KEY_7,
        KeyEvent.KEYCODE_8 to KEY_8,
        KeyEvent.KEYCODE_9 to KEY_9,

        // Function keys
        KeyEvent.KEYCODE_F1 to KEY_F1,
        KeyEvent.KEYCODE_F2 to KEY_F2,
        KeyEvent.KEYCODE_F3 to KEY_F3,
        KeyEvent.KEYCODE_F4 to KEY_F4,
        KeyEvent.KEYCODE_F5 to KEY_F5,
        KeyEvent.KEYCODE_F6 to KEY_F6,
        KeyEvent.KEYCODE_F7 to KEY_F7,
        KeyEvent.KEYCODE_F8 to KEY_F8,
        KeyEvent.KEYCODE_F9 to KEY_F9,
        KeyEvent.KEYCODE_F10 to KEY_F10,
        KeyEvent.KEYCODE_F11 to KEY_F11,
        KeyEvent.KEYCODE_F12 to KEY_F12,

        // Modifiers
        KeyEvent.KEYCODE_SHIFT_LEFT to KEY_LEFTSHIFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT to KEY_RIGHTSHIFT,
        KeyEvent.KEYCODE_CTRL_LEFT to KEY_LEFTCTRL,
        KeyEvent.KEYCODE_CTRL_RIGHT to KEY_RIGHTCTRL,
        KeyEvent.KEYCODE_ALT_LEFT to KEY_LEFTALT,
        KeyEvent.KEYCODE_ALT_RIGHT to KEY_RIGHTALT,
        KeyEvent.KEYCODE_META_LEFT to KEY_LEFTMETA,
        KeyEvent.KEYCODE_META_RIGHT to KEY_RIGHTMETA,

        // Special keys
        KeyEvent.KEYCODE_ENTER to KEY_ENTER,
        KeyEvent.KEYCODE_TAB to KEY_TAB,
        KeyEvent.KEYCODE_SPACE to KEY_SPACE,
        KeyEvent.KEYCODE_DEL to KEY_BACKSPACE,
        KeyEvent.KEYCODE_ESCAPE to KEY_ESC,
        KeyEvent.KEYCODE_FORWARD_DEL to KEY_DELETE,

        // Navigation
        KeyEvent.KEYCODE_DPAD_UP to KEY_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to KEY_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to KEY_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to KEY_RIGHT,
        KeyEvent.KEYCODE_PAGE_UP to KEY_PAGEUP,
        KeyEvent.KEYCODE_PAGE_DOWN to KEY_PAGEDOWN,
        KeyEvent.KEYCODE_MOVE_HOME to KEY_HOME,
        KeyEvent.KEYCODE_MOVE_END to KEY_END,
        KeyEvent.KEYCODE_INSERT to KEY_INSERT,

        // Punctuation
        KeyEvent.KEYCODE_MINUS to KEY_MINUS,
        KeyEvent.KEYCODE_EQUALS to KEY_EQUAL,
        KeyEvent.KEYCODE_LEFT_BRACKET to KEY_LEFTBRACE,
        KeyEvent.KEYCODE_RIGHT_BRACKET to KEY_RIGHTBRACE,
        KeyEvent.KEYCODE_BACKSLASH to KEY_BACKSLASH,
        KeyEvent.KEYCODE_SEMICOLON to KEY_SEMICOLON,
        KeyEvent.KEYCODE_APOSTROPHE to KEY_APOSTROPHE,
        KeyEvent.KEYCODE_GRAVE to KEY_GRAVE,
        KeyEvent.KEYCODE_COMMA to KEY_COMMA,
        KeyEvent.KEYCODE_PERIOD to KEY_DOT,
        KeyEvent.KEYCODE_SLASH to KEY_SLASH,

        // Numpad
        KeyEvent.KEYCODE_NUMPAD_0 to KEY_KP0,
        KeyEvent.KEYCODE_NUMPAD_1 to KEY_KP1,
        KeyEvent.KEYCODE_NUMPAD_2 to KEY_KP2,
        KeyEvent.KEYCODE_NUMPAD_3 to KEY_KP3,
        KeyEvent.KEYCODE_NUMPAD_4 to KEY_KP4,
        KeyEvent.KEYCODE_NUMPAD_5 to KEY_KP5,
        KeyEvent.KEYCODE_NUMPAD_6 to KEY_KP6,
        KeyEvent.KEYCODE_NUMPAD_7 to KEY_KP7,
        KeyEvent.KEYCODE_NUMPAD_8 to KEY_KP8,
        KeyEvent.KEYCODE_NUMPAD_9 to KEY_KP9,
        KeyEvent.KEYCODE_NUMPAD_DIVIDE to KEY_KPSLASH,
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY to KEY_KPASTERISK,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT to KEY_KPMINUS,
        KeyEvent.KEYCODE_NUMPAD_ADD to KEY_KPPLUS,
        KeyEvent.KEYCODE_NUMPAD_DOT to KEY_KPDOT,
        KeyEvent.KEYCODE_NUMPAD_ENTER to KEY_KPENTER,
        KeyEvent.KEYCODE_NUMPAD_EQUALS to KEY_KPEQUAL,
        KeyEvent.KEYCODE_NUM_LOCK to KEY_NUMLOCK,

        // Other
        KeyEvent.KEYCODE_CAPS_LOCK to KEY_CAPSLOCK,
        KeyEvent.KEYCODE_SCROLL_LOCK to KEY_SCROLLLOCK,
        KeyEvent.KEYCODE_BREAK to KEY_PAUSE,
        KeyEvent.KEYCODE_SYSRQ to KEY_SYSRQ,
    )
}
