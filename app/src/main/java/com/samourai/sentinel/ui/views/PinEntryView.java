package com.samourai.sentinel.ui.views;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;


import com.samourai.sentinel.R;
import com.samourai.sentinel.core.access.AccessFactory;
import com.samourai.sentinel.core.access.ScrambledPin;

/**
 * Re-Usable custom KeypadView for pin entry
 */
public class PinEntryView extends FrameLayout implements View.OnClickListener {


    public enum KeyClearTypes {
        CLEAR_ALL,
        CLEAR
    }

    private Button ta = null;
    private Button tb = null;
    private Button tc = null;
    private Button td = null;
    private Button te = null;
    private Button tf = null;
    private Button tg = null;
    private Button th = null;
    private Button ti = null;
    private Button tj = null;
    private ImageButton tconfirm = null;
    private ImageButton tback = null;
    private ScrambledPin keypad = null;
    private int pinLen = 0;
    private boolean scramble = false;
    private pinEntryListener entryListener = null;
    private pinClearListener clearListener = null;
    /** #415: true while the confirm slot shows the red error cross. */
    private boolean errorCrossShowing = false;
    private final Runnable errorCrossRestore = this::hideErrorCross;
     private View view;

    public PinEntryView(Context context) {
        super(context);
        initView();
    }

    public PinEntryView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public PinEntryView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView();
    }

    public void setEntryListener(pinEntryListener entryListener) {
        this.entryListener = entryListener;
    }

    public void setClearListener(pinClearListener clearListener) {
        this.clearListener = clearListener;
    }

    public void setScramble(boolean scramble) {
        this.scramble = scramble;
        setButtonLabels();
    }

    private void initView() {
        view = inflate(getContext(), R.layout.fragment_keypad_view, null);
        ta = view.findViewById(R.id.ta);
        tb = view.findViewById(R.id.tb);
        tc = view.findViewById(R.id.tc);
        td = view.findViewById(R.id.td);
        te = view.findViewById(R.id.te);
        tf = view.findViewById(R.id.tf);
        tg = view.findViewById(R.id.tg);
        th = view.findViewById(R.id.th);
        ti = view.findViewById(R.id.ti);
        tj = view.findViewById(R.id.tj);
        tconfirm = view.findViewById(R.id.tconfirm);
        tback = view.findViewById(R.id.tback);
        tback.setOnClickListener(view1 -> {
            hapticFeedBack();
            pinLen--;

            if (clearListener != null) {
                clearListener.onPinClear(KeyClearTypes.CLEAR);
            }
        });
        tback.setOnLongClickListener(view12 -> {
            pinLen = 0;
            if (clearListener != null) {
                clearListener.onPinClear(KeyClearTypes.CLEAR_ALL);
            }
            hapticFeedBack();
            return false;
        });
        setButtonLabels();
        addView(view);
    }

    @Override
    public void onClick(View view) {
        hapticFeedBack();
        if (pinLen <= (AccessFactory.MAX_PIN_LENGTH - 1)) {
            if (this.entryListener != null) {
                entryListener.onPinEntered(((Button) view).getText().toString(), view);
            }
            pinLen++;
        }
    }

    private void hapticFeedBack() {
        if (this.isHapticFeedbackEnabled()) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }


    public void showCheckButton() {
        if (errorCrossShowing) {
            hideErrorCross();
        }
        tconfirm.setVisibility(VISIBLE);
    }

    public void hideCheckButton() {
        if (errorCrossShowing) {
            hideErrorCross();
        }
        tconfirm.setVisibility(GONE);
    }

    public void setConfirmClickListener(OnClickListener clickListener) {
        tconfirm.setOnClickListener(clickListener);
    }

    public void removeUserInput() {
        this.pinLen = 0;
    }

    /**
     * #415: a rejected entry flashes a red cross in the confirm slot, the
     * same place the green check lives. The restore puts the check back and
     * re-decides visibility from the current entry length; show/hide of the
     * check during the window snaps the cross away instead.
     */
    public void flashErrorCross() {
        tconfirm.removeCallbacks(errorCrossRestore);
        tconfirm.setBackgroundResource(R.drawable.rounded_rectangle_red);
        tconfirm.setImageResource(R.drawable.ic_close_black_24dp);
        tconfirm.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
        tconfirm.setVisibility(VISIBLE);
        tconfirm.setEnabled(false);
        errorCrossShowing = true;
        tconfirm.postDelayed(errorCrossRestore, 600L);
    }

    private void hideErrorCross() {
        tconfirm.removeCallbacks(errorCrossRestore);
        errorCrossShowing = false;
        tconfirm.setBackgroundResource(R.drawable.rounded_rectangle_green);
        tconfirm.setImageResource(R.drawable.ic_done_white_24dp);
        tconfirm.setColorFilter(null);
        tconfirm.setEnabled(true);
        tconfirm.setVisibility(pinLen >= AccessFactory.MIN_PIN_LENGTH ? VISIBLE : GONE);
    }

    private void setButtonLabels() {
        keypad = new ScrambledPin();
        ta.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(0).getValue()) : "1");
        ta.setOnClickListener(this);
        tb.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(1).getValue()) : "2");
        tb.setOnClickListener(this);
        tc.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(2).getValue()) : "3");
        tc.setOnClickListener(this);
        td.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(3).getValue()) : "4");
        td.setOnClickListener(this);
        te.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(4).getValue()) : "5");
        te.setOnClickListener(this);
        tf.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(5).getValue()) : "6");
        tf.setOnClickListener(this);
        tg.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(6).getValue()) : "7");
        tg.setOnClickListener(this);
        th.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(7).getValue()) : "8");
        th.setOnClickListener(this);
        ti.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(8).getValue()) : "9");
        ti.setOnClickListener(this);
        tj.setText(this.scramble ? Integer.toString(keypad.getMatrix().get(9).getValue()) : "0");
        tj.setOnClickListener(this);
    }

    public interface pinEntryListener {
        void onPinEntered(String key, View view);
    }

    public interface pinClearListener {
        void onPinClear(KeyClearTypes clearType);
    }
}
