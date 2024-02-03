package com.think.music.util

import android.content.SharedPreferences

object SharedPreferenceUtil {

    fun saveCurrentPosition(sharedPreferences: SharedPreferences, currentPosition: Int) {
        with(sharedPreferences.edit()) {
            putInt(Constants.CURRENT_SONG_DURATION_KEY, currentPosition)
            apply()
        }
    }

    fun getPosition(sharedPreferences: SharedPreferences): Int {
        return sharedPreferences.getInt(Constants.POSITION_KEY, 0)
    }
}
