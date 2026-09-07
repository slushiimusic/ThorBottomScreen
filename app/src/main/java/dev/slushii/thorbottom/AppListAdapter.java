package dev.slushii.thorbottom;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AppListAdapter extends BaseAdapter {

    private final Context ctx;
    private final Prefs prefs;
    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private String filter = "";

    AppListAdapter(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
    }

    void setApps(List<AppEntry> apps) {
        all.clear();
        all.addAll(apps);
        applyFilter(filter);
    }

    List<AppEntry> allApps() { return all; }

    void applyFilter(String q) {
        filter = q == null ? "" : q.trim().toLowerCase(Locale.US);
        shown.clear();
        for (AppEntry e : all) {
            if (filter.isEmpty()
                    || e.label.toLowerCase(Locale.US).contains(filter)
                    || e.pkg.toLowerCase(Locale.US).contains(filter)) {
                shown.add(e);
            }
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return shown.size(); }
    @Override public AppEntry getItem(int position) { return shown.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView;
        if (v == null) {
            v = LayoutInflater.from(ctx).inflate(R.layout.row_app, parent, false);
        }
        AppEntry e = getItem(position);
        ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(e.icon);
        ((TextView) v.findViewById(R.id.label)).setText(e.label);
        ((TextView) v.findViewById(R.id.pkg)).setText(e.pkg);
        ((CheckBox) v.findViewById(R.id.check)).setChecked(prefs.isSelected(e.pkg));
        return v;
    }

    void toggle(AppEntry e) {
        prefs.setSelected(e.pkg, !prefs.isSelected(e.pkg));
        notifyDataSetChanged();
    }
}
