package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remote-only editor for the launcher dock. The preference is deliberately separate from config.xml
 * and a theme overlay: it is app data, so it survives a launcher APK update and remains the owner's
 * highest-priority dock choice until the owner changes it here.
 */
final class DockEditor {
    private static final String PREFS = "dock_preferences";
    private static final String KEY = "user_dock_v1";
    private static final String APP_ORDER_KEY = "user_app_order_v1";

    interface DismissListener {
        void onDismissed();
    }

    static final class TvApp {
        final String pkg;
        final String label;
        final Drawable icon;

        TvApp(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    static List<String> loadDock(Context ctx, List<String> configured, int maxSlots) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY)) return clean(configured, maxSlots);
        String raw = prefs.getString(KEY, "");
        List<String> saved = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            Collections.addAll(saved, raw.split("\\n"));
        }
        return clean(saved, maxSlots);
    }

    /**
     * Returns the user order for the apps below the dock, merging newly installed TV apps at the
     * end. An absent preference deliberately starts from the normal label order.
     */
    static List<String> loadAppOrder(Context ctx, List<String> dockApps) {
        List<TvApp> installed = queryInstalledApps(ctx);
        Set<String> dock = new HashSet<>(dockApps == null ? Collections.emptyList() : dockApps);
        Set<String> available = new HashSet<>();
        for (TvApp app : installed) if (!dock.contains(app.pkg)) available.add(app.pkg);

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> result = new ArrayList<>();
        if (prefs.contains(APP_ORDER_KEY)) {
            String raw = prefs.getString(APP_ORDER_KEY, "");
            if (raw != null && !raw.isEmpty()) {
                for (String pkg : raw.split("\\n")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty() && available.contains(pkg) && !result.contains(pkg)) result.add(pkg);
                }
            }
        }
        for (TvApp app : installed) {
            if (!dock.contains(app.pkg) && !result.contains(app.pkg)) result.add(app.pkg);
        }
        return result;
    }

    private static List<String> clean(List<String> values, int maxSlots) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String pkg = value.trim();
                if (!pkg.isEmpty()) unique.add(pkg);
                if (unique.size() == Math.max(1, maxSlots)) break;
            }
        }
        return new ArrayList<>(unique);
    }

    static void saveDock(Context ctx, List<String> dock) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, TextUtils.join("\n", dock))
                .apply();
    }

    static void saveAppOrder(Context ctx, List<String> order) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(APP_ORDER_KEY, TextUtils.join("\n", order))
                .apply();
    }

    private final Activity activity;
    private final int maxSlots;
    private final List<String> dock;
    private final DismissListener dismissListener;
    private final List<View> editorNavigation = new ArrayList<>();
    private final List<View> pickerNavigation = new ArrayList<>();
    private final List<View> orderNavigation = new ArrayList<>();

    private Dialog editor;
    private Dialog picker;
    private Dialog orderEditor;
    private LinearLayout editorRows;
    private int selectedIndex;
    /** Optional app to focus when the app-order editor is opened directly from a tile. */
    private String initialOrderPackage;
    private final Runnable appOrderChanged;

    DockEditor(Activity activity, List<String> initialDock, int maxSlots, DismissListener dismissListener) {
        this(activity, initialDock, maxSlots, dismissListener, null);
    }

    DockEditor(Activity activity, List<String> initialDock, int maxSlots,
               DismissListener dismissListener, Runnable appOrderChanged) {
        this.activity = activity;
        this.maxSlots = Math.max(1, maxSlots);
        this.dock = clean(initialDock, this.maxSlots);
        this.dismissListener = dismissListener;
        this.appOrderChanged = appOrderChanged;
        this.selectedIndex = this.dock.isEmpty() ? -1 : 0;
    }

    void show() {
        View panel = LayoutInflater.from(activity).inflate(R.layout.dialog_dock_editor, null, false);
        editorRows = panel.findViewById(R.id.rows);
        ((TextView) panel.findViewById(R.id.title)).setText(activity.getString(R.string.edit_dock));
        ((TextView) panel.findViewById(R.id.hint)).setText(
                activity.getString(R.string.edit_dock_hint, maxSlots));

        editor = new Dialog(activity);
        editor.requestWindowFeature(Window.FEATURE_NO_TITLE);
        editor.setContentView(panel);
        editor.setCanceledOnTouchOutside(false);
        editor.setOnDismissListener(d -> {
            if (picker != null) picker.dismiss();
            if (orderEditor != null) orderEditor.dismiss();
            if (dismissListener != null) dismissListener.onDismissed();
        });
        editor.show();
        sizeDialog(editor, 0.86f, 0.84f);
        rebuildEditorRows(selectedIndex < 0 ? 0 : selectedIndex);
    }

    /** Opens the dock editor with a particular visible dock slot selected. */
    void showDockAt(int index) {
        selectedIndex = dock.isEmpty() ? -1 : Math.max(0, Math.min(index, dock.size() - 1));
        show();
    }

    /** Opens only the full app-order editor, focused on the app that was long-pressed. */
    void showAppOrderOnly(String packageName) {
        initialOrderPackage = packageName;
        showAppOrderEditor();
    }

    private void rebuildEditorRows(int preferredFocus) {
        if (editorRows == null) return;
        editorRows.removeAllViews();
        editorNavigation.clear();

        for (int i = 0; i < dock.size(); i++) {
            final int index = i;
            View row = LayoutInflater.from(activity).inflate(R.layout.item_dock_row, editorRows, false);
            TvApp app = appFor(dock.get(i));
            ((TextView) row.findViewById(R.id.slot)).setText(activity.getString(R.string.dock_slot, i + 1));
            ((TextView) row.findViewById(R.id.title)).setText(app.label);
            ((TextView) row.findViewById(R.id.hint)).setText(R.string.dock_replace_hint);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(app.icon);
            row.setTag(index);
            row.setOnClickListener(v -> showPicker(index));
            row.setOnFocusChangeListener((v, focused) -> {
                if (focused) selectedIndex = index;
            });
            editorRows.addView(row);
            editorNavigation.add(row);
        }

        if (dock.size() < maxSlots) {
            View add = actionRow(R.drawable.ic_edit_dock, R.string.add_app_to_dock, 1);
            editorRows.addView(add);
            editorNavigation.add(add);
        }
        if (!dock.isEmpty()) {
            View remove = actionRow(R.drawable.ic_lock, R.string.remove_highlighted, 2);
            editorRows.addView(remove);
            editorNavigation.add(remove);
        }
        View order = actionRow(R.drawable.ic_edit_dock, R.string.edit_app_order, 4);
        editorRows.addView(order);
        editorNavigation.add(order);
        View done = actionRow(R.drawable.ic_settings, R.string.dock_done, 3);
        editorRows.addView(done);
        editorNavigation.add(done);

        for (View row : editorNavigation) row.setOnKeyListener(this::onEditorKey);
        int focus = Math.max(0, Math.min(preferredFocus, editorNavigation.size() - 1));
        editorRows.post(() -> {
            if (!editorNavigation.isEmpty()) editorNavigation.get(focus).requestFocus();
        });
    }

    private View actionRow(int icon, int title, int action) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_dock_action, editorRows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setTag(-action);
        row.setOnClickListener(v -> runEditorAction(action));
        return row;
    }

    private boolean onEditorKey(View view, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int navigationIndex = editorNavigation.indexOf(view);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = navigationIndex + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < editorNavigation.size()) editorNavigation.get(next).requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            Object tag = view.getTag();
            if (tag instanceof Integer && (Integer) tag >= 0) {
                int index = (Integer) tag;
                int next = index + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (next >= 0 && next < dock.size()) {
                    Collections.swap(dock, index, next);
                    selectedIndex = next;
                    saveDock(activity, dock);
                    rebuildEditorRows(next);
                }
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            Object tag = view.getTag();
            if (tag instanceof Integer) {
                int action = (Integer) tag;
                if (action >= 0) showPicker(action);
                else runEditorAction(-action);
            }
            return true;
        }
        return false;
    }

    /** App rows use their non-negative dock index; action rows use negative action ids. */
    private void showPicker(int replaceIndex) {
        if (replaceIndex < 0) {
            int action = -replaceIndex;
            if (action == 1) showPickerForAdd();
            else if (action == 2) removeSelected();
            else if (action == 3 && editor != null) editor.dismiss();
            return;
        }
        showPickerForReplace(replaceIndex);
    }

    private void runEditorAction(int action) {
        if (action == 1) showPickerForAdd();
        else if (action == 2) removeSelected();
        else if (action == 4) showAppOrderEditor();
        else if (action == 3 && editor != null) editor.dismiss();
    }

    private void showAppOrderEditor() {
        List<TvApp> apps = installedApps();
        List<String> order = loadAppOrder(activity, dock);
        Map<String, TvApp> byPackage = new HashMap<>();
        for (TvApp app : apps) byPackage.put(app.pkg, app);

        View panel = LayoutInflater.from(activity).inflate(R.layout.dialog_app_order, null, false);
        ((TextView) panel.findViewById(R.id.title)).setText(R.string.edit_app_order);
        ((TextView) panel.findViewById(R.id.hint)).setText(R.string.edit_app_order_hint);
        LinearLayout rows = panel.findViewById(R.id.rows);
        orderNavigation.clear();
        int preferredFocus = -1;
        for (String pkg : order) {
            TvApp app = byPackage.get(pkg);
            if (app == null) continue;
            View row = LayoutInflater.from(activity).inflate(R.layout.item_app_picker, rows, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) row.findViewById(R.id.title)).setText(app.label);
            ((TextView) row.findViewById(R.id.package_name)).setText(app.pkg);
            row.setTag(pkg);
            row.setOnKeyListener((v, keyCode, event) -> onAppOrderKey(v, keyCode, event, order));
            row.setOnClickListener(v -> { });
            rows.addView(row);
            orderNavigation.add(row);
            if (pkg.equals(initialOrderPackage)) preferredFocus = orderNavigation.size() - 1;
        }
        View done = LayoutInflater.from(activity).inflate(R.layout.item_dock_action, rows, false);
        ((ImageView) done.findViewById(R.id.icon)).setImageResource(R.drawable.ic_settings);
        ((TextView) done.findViewById(R.id.title)).setText(R.string.dock_done);
        done.setOnClickListener(v -> orderEditor.dismiss());
        done.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                orderEditor.dismiss();
                return true;
            }
            return onAppOrderNavigationKey(v, keyCode, event);
        });
        rows.addView(done);
        orderNavigation.add(done);

        orderEditor = new Dialog(activity);
        orderEditor.requestWindowFeature(Window.FEATURE_NO_TITLE);
        orderEditor.setContentView(panel);
        orderEditor.setCanceledOnTouchOutside(false);
        orderEditor.setOnDismissListener(d -> {
            orderEditor = null;
            if (appOrderChanged != null) appOrderChanged.run();
            for (View row : editorNavigation) {
                if (row.getTag() instanceof Integer && (Integer) row.getTag() == -4) {
                    row.requestFocus();
                    break;
                }
            }
        });
        orderEditor.show();
        sizeDialog(orderEditor, 0.80f, 0.84f);
        if (!orderNavigation.isEmpty()) {
            int focus = preferredFocus >= 0 ? preferredFocus : 0;
            orderNavigation.get(Math.min(focus, orderNavigation.size() - 1)).requestFocus();
        }
        initialOrderPackage = null;
    }

    private boolean onAppOrderNavigationKey(View view, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int index = orderNavigation.indexOf(view);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = index + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < orderNavigation.size()) orderNavigation.get(next).requestFocus();
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private boolean onAppOrderKey(View view, int keyCode, KeyEvent event, List<String> order) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int index = orderNavigation.indexOf(view);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = index + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < orderNavigation.size()) orderNavigation.get(next).requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            Object tag = view.getTag();
            int current = tag instanceof String ? order.indexOf(tag) : -1;
            int next = current + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (current >= 0 && next >= 0 && next < order.size()) {
                Collections.swap(order, current, next);
                saveAppOrder(activity, order);
                rebindAppOrderRows(order);
            }
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
    }

    private void rebindAppOrderRows(List<String> order) {
        if (orderEditor == null || !orderEditor.isShowing()) return;
        LinearLayout rows = orderEditor.findViewById(R.id.rows);
        if (rows == null) return;
        // Rebind in place so Android TV focus and ScrollView state remain stable after a swap.
        for (int i = 0; i < order.size() && i < rows.getChildCount() - 1; i++) {
            View row = rows.getChildAt(i);
            TvApp app = appFor(order.get(i));
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) row.findViewById(R.id.title)).setText(app.label);
            ((TextView) row.findViewById(R.id.package_name)).setText(app.pkg);
            row.setTag(order.get(i));
        }
    }

    private void showPickerForAdd() {
        if (dock.size() >= maxSlots) return;
        showPickerDialog(-1);
    }

    private void showPickerForReplace(int index) {
        if (index >= 0 && index < dock.size()) showPickerDialog(index);
    }

    private void showPickerDialog(int replaceIndex) {
        List<TvApp> apps = installedApps();
        Set<String> alreadyPinned = new HashSet<>(dock);
        if (replaceIndex >= 0 && replaceIndex < dock.size()) alreadyPinned.remove(dock.get(replaceIndex));
        List<TvApp> choices = new ArrayList<>();
        for (TvApp app : apps) {
            if (!alreadyPinned.contains(app.pkg)) choices.add(app);
        }
        if (choices.isEmpty()) {
            Toast.makeText(activity, R.string.no_tv_apps, Toast.LENGTH_SHORT).show();
            return;
        }

        View panel = LayoutInflater.from(activity).inflate(R.layout.dialog_app_picker, null, false);
        ((TextView) panel.findViewById(R.id.title)).setText(
                replaceIndex >= 0 ? R.string.replace_dock_app : R.string.add_app_to_dock);
        ((TextView) panel.findViewById(R.id.hint)).setText(R.string.choose_tv_app_hint);
        LinearLayout rows = panel.findViewById(R.id.rows);
        pickerNavigation.clear();
        if (replaceIndex >= 0) {
            View remove = LayoutInflater.from(activity).inflate(R.layout.item_dock_action, rows, false);
            ((ImageView) remove.findViewById(R.id.icon)).setImageResource(R.drawable.ic_lock);
            ((TextView) remove.findViewById(R.id.title)).setText(R.string.remove_from_dock);
            remove.setOnClickListener(v -> removeFromPicker(replaceIndex));
            rows.addView(remove);
            pickerNavigation.add(remove);
        }
        for (TvApp app : choices) {
            View row = LayoutInflater.from(activity).inflate(R.layout.item_app_picker, rows, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) row.findViewById(R.id.title)).setText(app.label);
            ((TextView) row.findViewById(R.id.package_name)).setText(app.pkg);
            row.setOnClickListener(v -> chooseApp(app, replaceIndex));
            rows.addView(row);
            pickerNavigation.add(row);
        }
        for (View row : pickerNavigation) row.setOnKeyListener(this::onPickerKey);

        picker = new Dialog(activity);
        picker.requestWindowFeature(Window.FEATURE_NO_TITLE);
        picker.setContentView(panel);
        picker.setCanceledOnTouchOutside(false);
        picker.setOnDismissListener(d -> {
            picker = null;
            if (editor != null && editor.isShowing()) {
                int focus = selectedIndex < 0 ? dock.size() : selectedIndex;
                editorRows.post(() -> {
                    if (focus >= 0 && focus < editorNavigation.size()) editorNavigation.get(focus).requestFocus();
                });
            }
        });
        picker.show();
        sizeDialog(picker, 0.80f, 0.84f);
        if (!pickerNavigation.isEmpty()) pickerNavigation.get(0).requestFocus();
    }

    private void removeFromPicker(int index) {
        if (index < 0 || index >= dock.size()) return;
        dock.remove(index);
        selectedIndex = Math.min(index, dock.size() - 1);
        saveDock(activity, dock);
        if (picker != null) picker.dismiss();
        rebuildEditorRows(selectedIndex < 0 ? 0 : selectedIndex);
    }

    private boolean onPickerKey(View view, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int index = pickerNavigation.indexOf(view);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = index + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
            if (next >= 0 && next < pickerNavigation.size()) pickerNavigation.get(next).requestFocus();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            view.performClick();
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private void chooseApp(TvApp app, int replaceIndex) {
        if (replaceIndex >= 0) {
            if (replaceIndex >= dock.size()) return;
            dock.set(replaceIndex, app.pkg);
            selectedIndex = replaceIndex;
        } else {
            if (dock.size() >= maxSlots || dock.contains(app.pkg)) return;
            dock.add(app.pkg);
            selectedIndex = dock.size() - 1;
        }
        saveDock(activity, dock);
        if (picker != null) picker.dismiss();
        rebuildEditorRows(selectedIndex);
    }

    private void removeSelected() {
        if (selectedIndex < 0 || selectedIndex >= dock.size()) return;
        dock.remove(selectedIndex);
        if (selectedIndex >= dock.size()) selectedIndex = dock.size() - 1;
        saveDock(activity, dock);
        rebuildEditorRows(selectedIndex < 0 ? 0 : selectedIndex);
    }

    private TvApp appFor(String pkg) {
        for (TvApp app : installedApps()) if (app.pkg.equals(pkg)) return app;
        return new TvApp(pkg, pkg, activity.getDrawable(android.R.drawable.ic_menu_help));
    }

    private List<TvApp> installedApps() {
        return queryInstalledApps(activity);
    }

    private static List<TvApp> queryInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, TvApp> byPackage = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || byPackage.containsKey(pkg)) continue;
            CharSequence rawLabel = ri.loadLabel(pm);
            String label = rawLabel == null ? pkg : rawLabel.toString().trim();
            byPackage.put(pkg, new TvApp(pkg, label.isEmpty() ? pkg : label, ri.loadIcon(pm)));
        }
        List<TvApp> apps = new ArrayList<>(byPackage.values());
        Collections.sort(apps, (a, b) -> {
            int label = a.label.compareToIgnoreCase(b.label);
            return label != 0 ? label : a.pkg.compareTo(b.pkg);
        });
        return apps;
    }

    private void sizeDialog(Dialog dialog, float widthFraction, float heightFraction) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.dimAmount = 0.62f;
        window.setAttributes(attrs);
        int width = Math.min(dp(720), Math.round(activity.getResources().getDisplayMetrics().widthPixels * widthFraction));
        int height = Math.min(dp(640), Math.round(activity.getResources().getDisplayMetrics().heightPixels * heightFraction));
        window.setLayout(Math.max(dp(420), width), Math.max(dp(320), height));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
