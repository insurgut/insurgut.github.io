package dex.mandre;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MandreSettingsDex { 

    public static final int EVENT_LOG = 1;
    public static final int EVENT_SET_SETTING = 2;
    public static final int EVENT_CLICK = 3;

    private static Class<?> UItemClass;
    private static Class<?> SwitchClass;
    private static Class<?> CheckBox2Class;
    private static Class<?> ThemeClass;
    private static Class<?> NumberPickerClass;
    
    private static Method asCustomMethod;
    private static Method asHeaderMethod;
    private static Method getColorMethod;
    private static Method getSelectorDrawableMethod;

    public static ArrayList<Object> parse(String dsl, HashMap<String, String> settingsMap, Handler callback, Context context) {
        ArrayList<Object> items = new ArrayList<>();
        try {
            if (dsl == null || context == null) return items;
            if (settingsMap == null) settingsMap = new HashMap<>();

            if (UItemClass == null) initReflection(context);

            dsl = dsl.replace("\r", "");
            Pattern tagPattern = Pattern.compile("<([a-zA-Z0-9_\\-]+)\\s*([^>]*)>(.*?)</\\1>|<([a-zA-Z0-9_\\-]+)\\s*([^>]*)/>", Pattern.DOTALL);
            Matcher matcher = tagPattern.matcher(dsl);

            while (matcher.find()) {
                String tagName = matcher.group(1);
                String attrsStr = matcher.group(2);
                String innerText = matcher.group(3);

                if (tagName == null) {
                    tagName = matcher.group(4);
                    attrsStr = matcher.group(5);
                    innerText = "";
                }

                Map<String, String> attrs = parseAttributes(attrsStr);
                if (innerText != null && !innerText.isEmpty() && !attrs.containsKey("text")) {
                    attrs.put("text", innerText.trim());
                }

                Object item = createElement(context, tagName.toLowerCase(), attrs, settingsMap, callback);
                if (item != null) items.add(item);
            }
        } catch (Exception e) {
            sendLog(callback, "Parse Error: " + e);
            e.printStackTrace();
        }
        return items;
    }

    private static void initReflection(Context ctx) throws Exception {
        ClassLoader cl = ctx.getClassLoader();
        UItemClass = cl.loadClass("org.telegram.ui.Components.UItem");
        asCustomMethod = UItemClass.getDeclaredMethod("asCustom", View.class);
        try { asHeaderMethod = UItemClass.getDeclaredMethod("asHeader", String.class); } catch (Exception e) {}

        ThemeClass = cl.loadClass("org.telegram.ui.ActionBar.Theme");
        getColorMethod = ThemeClass.getMethod("getColor", int.class);
        try { getSelectorDrawableMethod = ThemeClass.getMethod("getSelectorDrawable", boolean.class); } catch (Exception e) {}

        try { SwitchClass = cl.loadClass("org.telegram.ui.Components.Switch"); } catch(Exception e) {}
        try { CheckBox2Class = cl.loadClass("org.telegram.ui.Components.CheckBox2"); } catch(Exception e) {}
        try { NumberPickerClass = cl.loadClass("org.telegram.ui.Components.NumberPicker"); } catch(Exception e) {}
    }

    private static Object createElement(Context ctx, String type, Map<String, String> attrs, HashMap<String, String> settings, Handler callback) throws Exception {
        String text = attrs.getOrDefault("text", "");
        String subtext = attrs.get("subtext");
        String key = attrs.containsKey("key") ? attrs.get("key") : attrs.get("name");
        String icon = attrs.get("icon");

        boolean defBool = "true".equalsIgnoreCase(attrs.get("default"));
        boolean currentBool = defBool;
        if (settings.containsKey(key)) currentBool = "true".equalsIgnoreCase(settings.get(key));

        switch (type) {
            case "header":
            case "section":
                // Если есть нативный метод и нет кастомного выравнивания
                if (asHeaderMethod != null && !attrs.containsKey("align")) {
                    return asHeaderMethod.invoke(null, text);
                }
                return createHeaderViewUItem(ctx, text, attrs);

            case "divider":
            case "shadow":
                return createDividerUItem(ctx, text);

            case "text":
            case "button":
                View tv = createUniversalCell(ctx, text, subtext, attrs, null);
                if (attrs.containsKey("on_click")) {
                    final String method = attrs.get("on_click");
                    tv.setOnClickListener(v -> sendClick(callback, method));
                }
                return asCustomMethod.invoke(null, tv);

            case "switch":
                View sw = createTgSwitch(ctx, currentBool);
                View swCell = createUniversalCell(ctx, text, subtext, attrs, sw);
                swCell.setOnClickListener(v -> {
                     performToggleOnChild(sw); 
                     boolean state = isChildChecked(sw);
                     sendSetSetting(callback, key, String.valueOf(state));
                });
                return asCustomMethod.invoke(null, swCell);

            case "checkbox":
                View cb = createTgCheckBox(ctx, currentBool);
                View cbCell = createUniversalCell(ctx, text, subtext, attrs, cb);
                cbCell.setOnClickListener(v -> {
                    performToggleOnChild(cb);
                    boolean state = isChildChecked(cb);
                    sendSetSetting(callback, key, String.valueOf(state));
                });
                return asCustomMethod.invoke(null, cbCell);

            case "slider":
                int min = parseInt(attrs.get("min"), 0);
                int max = parseInt(attrs.get("max"), 100);
                int defInt = parseInt(attrs.get("default"), 50);
                int currentInt = parseInt(settings.get(key), defInt);
                
                View sliderCell = createSliderCell(ctx, text, min, max, currentInt, attrs, (val) -> {
                    sendSetSetting(callback, key, String.valueOf(val));
                });
                return asCustomMethod.invoke(null, sliderCell);

            case "selector":
            case "numberpicker":
                String[] items = attrs.getOrDefault("items", "").split(",");
                int defIdx = parseInt(attrs.get("default"), 0);
                int curIdx = parseInt(settings.get(key), defIdx);
                for(int i=0; i<items.length; i++) items[i] = items[i].trim();

                final String[] finalItems = items;
                
                String mode = attrs.getOrDefault("mode", "dialog");

                if ("inline".equalsIgnoreCase(mode)) {
                    View inlineSelector = createInlineSelector(ctx, text, subtext, attrs, finalItems, curIdx, (newVal) -> {
                        sendSetSetting(callback, key, String.valueOf(newVal));
                    });
                    return asCustomMethod.invoke(null, inlineSelector);
                } else {
                    String valText = (curIdx >= 0 && curIdx < items.length) ? items[curIdx] : "";
                    String displaySub = (subtext != null && !subtext.isEmpty()) ? subtext + "\n" + valText : valText;
                    if (!attrs.containsKey("subtext_color")) attrs.put("subtext_color", "windowBackgroundWhiteBlueText");

                    View selCell = createUniversalCell(ctx, text, displaySub, attrs, null);
                    
                    final TextView[] subTvRef = {null};
                    findTextViewRecursive(selCell, displaySub, subTvRef);

                    selCell.setOnClickListener(v -> {
                        showNumberPickerDialog(ctx, text, finalItems, curIdx, (resIdx) -> {
                            if (subTvRef[0] != null) {
                                String newDisplaySub = (subtext != null && !subtext.isEmpty()) ? subtext + "\n" + finalItems[resIdx] : finalItems[resIdx];
                                subTvRef[0].setText(newDisplaySub);
                            }
                            sendSetSetting(callback, key, String.valueOf(resIdx));
                        });
                    });
                    return asCustomMethod.invoke(null, selCell);
                }
        }
        return null;
    }

    // --- Component Implementations ---

    private static View createInlineSelector(Context ctx, String text, String subtext, Map<String, String> attrs, String[] items, int current, OnIntChange callback) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(getBgDrawable());
        
        // Делаем ВЕСЬ root кликабельным
        root.setClickable(true);
        root.setFocusable(true);

        String valText = (current >= 0 && current < items.length) ? items[current] : "";
        String displaySub = (subtext != null && !subtext.isEmpty()) ? subtext + "\n" + valText : valText;
        
        if (!attrs.containsKey("subtext_color")) attrs.put("subtext_color", "windowBackgroundWhiteBlueText");
        
        // Заголовок (Header Cell)
        // Важно: передаем null в качестве accessory, чтобы не создавать лишних View
        View header = createUniversalCell(ctx, text, displaySub, attrs, null);
        
        // ОТКЛЮЧАЕМ кликабельность у header, чтобы клик проходил в root
        header.setClickable(false); 
        header.setBackground(null); 
        
        root.addView(header);

        // Контейнер для Picker
        LinearLayout pickerContainer = new LinearLayout(ctx);
        pickerContainer.setOrientation(LinearLayout.VERTICAL);
        pickerContainer.setVisibility(View.GONE);
        pickerContainer.setPadding(0, 0, 0, dp(ctx, 10));
        
        final TextView[] subTvRef = {null};
        findTextViewRecursive(header, displaySub, subTvRef);

        View picker = createRawNumberPicker(ctx, items, current, (val) -> {
            if (subTvRef[0] != null) {
                String newDisplaySub = (subtext != null && !subtext.isEmpty()) ? subtext + "\n" + items[val] : items[val];
                subTvRef[0].setText(newDisplaySub);
            }
            if (callback != null) callback.run(val);
        });
        
        // Блокировка перехвата тачей для скролла
        picker.setOnTouchListener((v, event) -> {
             if(event.getAction() == MotionEvent.ACTION_DOWN) v.getParent().requestDisallowInterceptTouchEvent(true);
             else if (event.getAction() == MotionEvent.ACTION_UP) v.getParent().requestDisallowInterceptTouchEvent(false);
             return false;
        });

        picker.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 180)));
        pickerContainer.addView(picker);
        root.addView(pickerContainer);

        // МГНОВЕННЫЙ ОБРАБОТЧИК КЛИКА
        root.setOnClickListener(v -> {
            boolean isVisible = pickerContainer.getVisibility() == View.VISIBLE;
            pickerContainer.setVisibility(isVisible ? View.GONE : View.VISIBLE);
            
            // Опционально: Анимация (TransitionManager) если доступен
            // Но для скорости пока просто visibility
        });

        return root;
    }

    private static View createSliderCell(Context ctx, String text, int min, int max, int current, Map<String, String> attrs, OnIntChange onChange) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(getBgDrawable());
        root.setPadding(dp(ctx, 21), dp(ctx, 12), dp(ctx, 21), dp(ctx, 4));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        
        String align = attrs.getOrDefault("align", "left");
        int gravity = parseGravity(align);

        TextView titleTv = new TextView(ctx);
        titleTv.setText(text);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleTv.setTextColor(getThemeColor(ctx, "windowBackgroundWhiteBlackText"));
        titleTv.setGravity(gravity);
        
        header.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1.0f));

        TextView valueTv = new TextView(ctx);
        valueTv.setText(String.valueOf(current));
        valueTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        valueTv.setTextColor(getThemeColor(ctx, "windowBackgroundWhiteBlueText"));
        valueTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(valueTv);

        root.addView(header);

        SeekBar seekBar = new SeekBar(ctx);
        int range = max - min;
        int progress = current - min;
        
        seekBar.setMax(range);
        seekBar.setProgress(progress);
        
        try {
            int active = getThemeColor(ctx, "switchTrackChecked");
            if (active != 0) {
                seekBar.getThumb().setColorFilter(active, PorterDuff.Mode.SRC_IN);
                seekBar.getProgressDrawable().setColorFilter(active, PorterDuff.Mode.SRC_IN);
            }
        } catch (Exception e) {}

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int realVal = progress + min;
                valueTv.setText(String.valueOf(realVal));
            }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {
                int realVal = seekBar.getProgress() + min;
                if (onChange != null) onChange.run(realVal);
            }
        });
        
        LinearLayout.LayoutParams lpSeek = new LinearLayout.LayoutParams(-1, -2);
        lpSeek.topMargin = dp(ctx, 10);
        lpSeek.leftMargin = -dp(ctx, 10);
        lpSeek.rightMargin = -dp(ctx, 10);
        root.addView(seekBar, lpSeek);

        return root;
    }

    private static View createUniversalCell(Context ctx, String text, String subtext, Map<String, String> attrs, View accessory) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(ctx, 21), dp(ctx, 10), dp(ctx, 21), dp(ctx, 10));
        root.setBackground(getBgDrawable());
        root.setMinimumHeight(dp(ctx, 50));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        String iconsStr = attrs.getOrDefault("icons", attrs.get("icon"));
        ArrayList<String> leftIcons = new ArrayList<>();
        ArrayList<String> rightIcons = new ArrayList<>();

        if (iconsStr != null && !iconsStr.isEmpty()) {
            String[] parts = iconsStr.split(",");
            for (String part : parts) {
                part = part.trim();
                if (part.endsWith(":right")) rightIcons.add(part.replace(":right", ""));
                else if (part.endsWith(":left")) leftIcons.add(part.replace(":left", ""));
                else leftIcons.add(part);
            }
        }

        for (String iconName : leftIcons) root.addView(createIconView(ctx, iconName, false));

        LinearLayout textLayout = new LinearLayout(ctx);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        
        String align = attrs.getOrDefault("align", attrs.getOrDefault("text_align", "left"));
        int gravity = parseGravity(align);
        textLayout.setGravity(gravity);

        TextView title = new TextView(ctx);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        int titleColor = getThemeColor(ctx, "windowBackgroundWhiteBlackText");
        if ("true".equals(attrs.get("red"))) titleColor = 0xFFFF4444;
        else if ("true".equals(attrs.get("accent"))) titleColor = getThemeColor(ctx, "windowBackgroundWhiteBlueText");
        title.setTextColor(titleColor);
        title.setGravity(gravity);
        textLayout.addView(title);

        if (subtext != null && !subtext.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(subtext);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            String subColorKey = attrs.getOrDefault("subtext_color", "windowBackgroundWhiteGrayText2");
            sub.setTextColor(getThemeColor(ctx, subColorKey));
            sub.setPadding(0, dp(ctx, 3), 0, 0);
            sub.setGravity(gravity);
            textLayout.addView(sub);
        }

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        if (!rightIcons.isEmpty() || accessory != null) textLp.rightMargin = dp(ctx, 16);
        root.addView(textLayout, textLp);

        for (String iconName : rightIcons) root.addView(createIconView(ctx, iconName, true));

        if (accessory != null) root.addView(accessory);

        return root;
    }

    private static ImageView createIconView(Context ctx, String iconName, boolean isRight) {
        ImageView iv = new ImageView(ctx);
        int resId = getResId(ctx, iconName);
        if (resId != 0) {
            iv.setImageResource(resId);
            int iconColor = getThemeColor(ctx, "windowBackgroundWhiteGrayIcon");
            iv.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int size = dp(ctx, 29);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        if (!isRight) lp.rightMargin = dp(ctx, 18);
        else lp.leftMargin = dp(ctx, 12);
        iv.setLayoutParams(lp);
        return iv;
    }

    // --- Wrapper methods ---
    
    private static Object createHeaderViewUItem(Context ctx, String text, Map<String, String> attrs) throws Exception {
        return asCustomMethod.invoke(null, createHeaderView(ctx, text, attrs));
    }

    private static View createHeaderView(Context ctx, String text, Map<String, String> attrs) {
        FrameLayout fl = new FrameLayout(ctx);
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(getThemeColor(ctx, "windowBackgroundWhiteBlueHeader"));
        
        String align = attrs != null ? attrs.getOrDefault("align", "left") : "left";
        int gravity = parseGravity(align);
        
        tv.setGravity(gravity | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(ctx, 21), dp(ctx, 15), dp(ctx, 21), dp(ctx, 8));
        
        fl.addView(tv, new FrameLayout.LayoutParams(-1, -2));
        return fl;
    }

    // --- Utils & Base ---

    private static Object createDividerUItem(Context ctx, String text) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(getThemeColor(ctx, "windowBackgroundGray"));
        
        if (text != null && !text.isEmpty()) {
            TextView tv = new TextView(ctx);
            tv.setText(text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tv.setTextColor(getThemeColor(ctx, "windowBackgroundWhiteGrayText2"));
            tv.setPadding(dp(ctx, 21), dp(ctx, 7), dp(ctx, 21), dp(ctx, 7));
            ll.addView(tv);
        } else {
            View v = new View(ctx);
            v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(ctx, 10))); 
            ll.addView(v);
        }
        try { return asCustomMethod.invoke(null, ll); } catch (Exception e) { return null; }
    }

    private static View createTgSwitch(Context ctx, boolean checked) throws Exception {
        Constructor<?> ctor = SwitchClass.getConstructor(Context.class);
        View switchView = (View) ctor.newInstance(ctx);
        Method setChecked = SwitchClass.getMethod("setChecked", boolean.class, boolean.class);
        setChecked.invoke(switchView, checked, false);
        Method setColors = SwitchClass.getMethod("setColors", int.class, int.class, int.class, int.class);
        setColors.invoke(switchView, getThemeKey("switchTrack"), getThemeKey("switchTrackChecked"), getThemeKey("windowBackgroundWhite"), getThemeKey("windowBackgroundWhite"));
        switchView.setLayoutParams(new ViewGroup.LayoutParams(dp(ctx, 38), dp(ctx, 40)));
        return switchView;
    }

    private static View createTgCheckBox(Context ctx, boolean checked) throws Exception {
        Constructor<?> ctor = CheckBox2Class.getConstructor(Context.class, int.class);
        View checkBox = (View) ctor.newInstance(ctx, 21); 
        Method setChecked = CheckBox2Class.getMethod("setChecked", boolean.class, boolean.class);
        setChecked.invoke(checkBox, checked, false);
        Method setColor = CheckBox2Class.getMethod("setColor", int.class, int.class, int.class);
        setColor.invoke(checkBox, getThemeKey("checkboxSquareUnchecked"), getThemeKey("checkboxSquareBackground"), getThemeKey("checkboxSquareCheck"));
        checkBox.setLayoutParams(new ViewGroup.LayoutParams(dp(ctx, 24), dp(ctx, 24)));
        return checkBox;
    }
    
    private static void performToggleOnChild(View child) {
        try {
            if (child.getClass().getName().contains("Switch")) {
                Method isCheckedM = SwitchClass.getMethod("isChecked");
                boolean state = (boolean) isCheckedM.invoke(child);
                Method setChecked = SwitchClass.getMethod("setChecked", boolean.class, boolean.class);
                setChecked.invoke(child, !state, true);
            } else if (child.getClass().getName().contains("CheckBox")) {
                Method isCheckedM = CheckBox2Class.getMethod("isChecked");
                boolean state = (boolean) isCheckedM.invoke(child);
                Method setChecked = CheckBox2Class.getMethod("setChecked", boolean.class, boolean.class);
                setChecked.invoke(child, !state, true);
            }
        } catch (Exception e) {}
    }
    
    private static boolean isChildChecked(View child) {
        try {
             Method isCheckedM = child.getClass().getMethod("isChecked");
             return (boolean) isCheckedM.invoke(child);
        } catch(Exception e) { return false; }
    }

    private static View createRawNumberPicker(Context ctx, String[] items, int current, OnIntChange onChange) {
        try {
            Constructor<?> ctor = NumberPickerClass.getConstructor(Context.class);
            View picker = (View) ctor.newInstance(ctx);
            
            Method setMinValue = NumberPickerClass.getMethod("setMinValue", int.class);
            Method setMaxValue = NumberPickerClass.getMethod("setMaxValue", int.class);
            Method setDisplayedValues = NumberPickerClass.getMethod("setDisplayedValues", String[].class);
            Method setValue = NumberPickerClass.getMethod("setValue", int.class);
            Method setOnValueChangedListener = NumberPickerClass.getMethod("setOnValueChangedListener", NumberPickerClass.getDeclaredClasses()[2]); 
            
            try {
                Method setTextColor = NumberPickerClass.getMethod("setTextColor", int.class);
                Method setSelectorColor = NumberPickerClass.getMethod("setSelectorColor", int.class);
                setTextColor.invoke(picker, getThemeColor(ctx, "windowBackgroundWhiteBlackText"));
                setSelectorColor.invoke(picker, getThemeColor(ctx, "windowBackgroundWhiteBlueText"));
            } catch (Exception e) {}

            setMinValue.invoke(picker, 0);
            setMaxValue.invoke(picker, items.length - 1);
            setDisplayedValues.invoke(picker, (Object) items);
            setValue.invoke(picker, current);

            Class<?> listenerInterface = null;
            for(Class<?> c : NumberPickerClass.getDeclaredClasses()) {
                if(c.getSimpleName().equals("OnValueChangeListener")) {
                    listenerInterface = c; break;
                }
            }
            
            if(listenerInterface != null) {
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerInterface.getClassLoader(),
                    new Class<?>[]{listenerInterface},
                    (proxyObj, method, args) -> {
                        if(method.getName().equals("onValueChange")) {
                            int newVal = (int) args[2];
                            if(onChange != null) onChange.run(newVal);
                        }
                        return null;
                    }
                );
                setOnValueChangedListener.invoke(picker, proxy);
            }
            return picker;
        } catch (Exception e) {
            TextView tv = new TextView(ctx);
            tv.setText("Picker Error: " + e);
            return tv;
        }
    }

    private static void showNumberPickerDialog(Context ctx, String title, String[] items, int current, OnIntChange callback) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setTitle(title);
            LinearLayout container = new LinearLayout(ctx);
            container.setGravity(Gravity.CENTER);
            container.setPadding(0, dp(ctx, 16), 0, dp(ctx, 16));
            container.setBackgroundColor(getThemeColor(ctx, "dialogBackground")); 
            
            View picker = createRawNumberPicker(ctx, items, current, null);
            
            try {
                 Method setTextColor = NumberPickerClass.getMethod("setTextColor", int.class);
                 Method setSelectorColor = NumberPickerClass.getMethod("setSelectorColor", int.class);
                 setTextColor.invoke(picker, getThemeColor(ctx, "dialogTextBlack"));
                 setSelectorColor.invoke(picker, getThemeColor(ctx, "dialogTextBlue"));
            } catch(Exception e) {}
            
            container.addView(picker, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            builder.setView(container);
            
            builder.setPositiveButton("Done", (dialog, which) -> {
                try {
                    Method getValue = NumberPickerClass.getMethod("getValue");
                    int val = (int) getValue.invoke(picker);
                    if (callback != null) callback.run(val);
                } catch (Exception e) {}
            });
            builder.setNegativeButton("Cancel", null);
            AlertDialog dialog = builder.create();
            dialog.show();
            
             try {
                int btnColor = getThemeColor(ctx, "dialogTextBlue");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(btnColor);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(btnColor);
            } catch(Exception e) {}
        } catch (Exception e) {}
    }

    private static int parseGravity(String align) {
        if ("center".equalsIgnoreCase(align)) return Gravity.CENTER;
        if ("right".equalsIgnoreCase(align)) return Gravity.RIGHT;
        return Gravity.LEFT;
    }
    
    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch(Exception e) { return def; }
    }
    
    private static void findTextViewRecursive(View v, String text, TextView[] out) {
        if (out[0] != null) return;
        if (v instanceof TextView) {
            if (text.equals(((TextView) v).getText().toString())) {
                out[0] = (TextView) v;
                return;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i=0; i<vg.getChildCount(); i++) findTextViewRecursive(vg.getChildAt(i), text, out);
        }
    }

    private static Drawable getBgDrawable() {
        try {
            return (Drawable) getSelectorDrawableMethod.invoke(null, false);
        } catch (Exception e) { return null; }
    }

    private static void sendLog(Handler h, String msg) {
        if (h == null) return;
        Message m = Message.obtain();
        m.what = EVENT_LOG;
        m.obj = msg;
        h.sendMessage(m);
    }

    private static void sendClick(Handler h, String method) {
        if (h == null) return;
        Message m = Message.obtain();
        m.what = EVENT_CLICK;
        m.obj = method;
        h.sendMessage(m);
    }

    private static void sendSetSetting(Handler h, String key, String value) {
        if (h == null) return;
        Message m = Message.obtain();
        m.what = EVENT_SET_SETTING;
        Bundle b = new Bundle();
        b.putString("key", key);
        b.putString("value", value);
        m.setData(b);
        h.sendMessage(m);
    }

    private static Map<String, String> parseAttributes(String attrStr) {
        Map<String, String> attrs = new HashMap<>();
        if (attrStr == null) return attrs;
        Pattern attrPattern = Pattern.compile("([\\w\\-]+)\\s*=\\s*\"(.*?)\"|([\\w\\-]+)\\s*=\\s*([^\\s\"]+)");
        Matcher m = attrPattern.matcher(attrStr);
        while (m.find()) {
            if (m.group(1) != null) attrs.put(m.group(1), m.group(2));
            else attrs.put(m.group(3), m.group(4));
        }
        return attrs;
    }

    private interface OnBoolChange { void run(boolean val); }
    private interface OnIntChange { void run(int val); }

    private static int dp(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    private static int getThemeKey(String key) {
        try {
            java.lang.reflect.Field field = ThemeClass.getField("key_" + key);
            return field.getInt(null);
        } catch (Exception e) { return 0; }
    }

    private static int getThemeColor(Context ctx, String key) {
        try {
            int keyInt = getThemeKey(key);
            return (int) getColorMethod.invoke(null, keyInt);
        } catch (Exception e) { return 0xFF000000; }
    }

    private static int getResId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
    }
}
