package com.geckour.egret.view.activity

import android.content.Context
import android.support.v7.preference.PreferenceManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.trello.rxlifecycle2.components.support.RxAppCompatActivity

open class BaseActivity: RxAppCompatActivity() {
    fun showSoftKeyBoardOnFocusEditText(et: EditText, hideOnUnFocus: Boolean = true) {
        et.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            else if (hideOnUnFocus) (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
        }
        et.requestFocusFromTouch()
        et.requestFocus()
    }

    fun hideSoftKeyBoard(et: EditText) = (et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(et.windowToken, 0)

    fun isModeDark(): Boolean = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("switch_to_dark_theme", false)
}