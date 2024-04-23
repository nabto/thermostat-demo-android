package com.nabto.edge.sharedcode

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.NavController
import androidx.navigation.navOptions
import androidx.preference.DialogPreference
import androidx.preference.Preference
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar

/**
 * LiveEvent is similar to LiveData except it wont resend the latest event to new observers.
 */
interface LiveEvent<T> {
    fun observe(owner: LifecycleOwner, obs: Observer<T>)
}

class MutableLiveEvent<T> : LiveEvent<T> {
    private val observers = mutableListOf<Observer<T>>()

    override fun observe(owner: LifecycleOwner, obs: Observer<T>) {
        observers.add(obs)
        owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                observers.remove(obs)
            }
        })
    }

    fun postEvent(event: T) {
        observers.forEach { obs ->
            obs.onChanged(event)
        }
    }
}

/**
 * Convenience function for making snackbars.
 * @param[msg] the message to display in the snackbar.
 * @param[duration] the duration that the snackbar will be visible for.
 */
fun View.snack(
    msg: String,
    duration: Int = Snackbar.LENGTH_LONG
): Snackbar {
    val snack = Snackbar.make(this, msg, duration)
    snack.setAnchorView(R.id.bottom_nav)
    snack.show()
    return snack
}

/**
 * Convenience function for having a fragment clear its current focus and hiding the keyboard
 * if it is visible.
 */
fun Fragment.clearFocusAndHideKeyboard() {
    val activity = requireActivity()
    val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
    val view = activity.currentFocus
    if (view != null) {
        view.clearFocus()
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

/**
 * Convenience function. Changes the view's visibility to GONE and changes other's view to VISIBLE.
 */
fun View.swapWith(other: View) {
    this.visibility = View.GONE
    other.visibility = View.VISIBLE
}


/**
 * Why doesn't androidx.preferences have something like this by default...?
 */
class ConfirmDialogPreference(context: Context) : Preference(context) {
    var dialogTitle: String = ""
    var dialogMessage: String = ""
    var dialogPositiveButton: String = context.getString(R.string.confirm)
    var dialogNegativeButton: String = context.getString(R.string.cancel)
    var onDialogClosed: (positive: Boolean) -> Unit = {}

    override fun onClick() {
        super.onClick()
        val builder = AlertDialog.Builder(context)
        builder
            .setTitle(dialogTitle)
            .setMessage(dialogMessage)
            .setPositiveButton(dialogPositiveButton) { _, _ ->
                onDialogClosed(true)
            }
            .setNegativeButton(dialogNegativeButton) { _, _ ->
                onDialogClosed(false)
            }
            .create()
            .show()
    }
}

class BasicDialogPreference(context: Context) : Preference(context) {
    var dialogTitle: String = ""
    var dialogMessage: String = ""
    var dialogPositiveButton: String = context.getString(R.string.ok)

    override fun onClick() {
        super.onClick()
        val builder = AlertDialog.Builder(context)
        builder.apply {
            setTitle(dialogTitle)
            setMessage(dialogMessage)
            setPositiveButton(dialogPositiveButton) { _, _ -> }
            create()
            show()
        }
    }
}

fun Fragment.requireAppActivity(): AppMainActivity {
    val activity = requireActivity()
    if (AppMainActivity::class.java.isAssignableFrom(activity.javaClass)) {
        return activity as AppMainActivity
    } else {
        throw IllegalStateException("Activity does not inherit from AppMainActivity.")
    }
}