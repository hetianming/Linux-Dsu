/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc.gl

/**
 * Shaders used for rendering framebuffer
 */
object Shaders {
    //language=GLSL
    const val VERTEX_SHADER = """
            uniform mat4 u_Projection;
            attribute vec2 a_Position;
            attribute vec2 a_TextureCoordinates;
            varying vec2 v_TextureCoordinates;
            void main()
            {
               v_TextureCoordinates = a_TextureCoordinates;
               gl_Position = u_Projection * vec4(a_Position, 0, 1);
            }"""

    //language=GLSL
    const val FRAGMENT_SHADER = """
             precision mediump float;
             uniform sampler2D u_TextureUnit;
             uniform bool u_UseTextureAlpha;
             varying vec2 v_TextureCoordinates;
             void main()
             {
                // RFB sends little-endian BGRX. The unused X byte is not alpha.
                vec4 pixel = texture2D(u_TextureUnit, v_TextureCoordinates);
                gl_FragColor = vec4(pixel.bgr, u_UseTextureAlpha ? pixel.a : 1.0);
             }"""
}
