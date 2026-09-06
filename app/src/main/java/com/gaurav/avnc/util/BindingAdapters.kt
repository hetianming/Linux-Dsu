/*
 * Copyright (c) 2020 Gaurav Ujjwal.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaurav.avnc.util

import android.view.View
import androidx.databinding.BindingAdapter

@BindingAdapter("isVisible")
fun setVisible(view: View, visible: Boolean) {
    view.visibility = if (visible) View.VISIBLE else View.GONE
}
