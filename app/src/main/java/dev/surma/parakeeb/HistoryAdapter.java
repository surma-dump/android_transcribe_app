package dev.surma.parakeeb;

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class HistoryAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<TranscriptEntry> entries = new ArrayList<>();

    public HistoryAdapter(Context context) {
        this.inflater = LayoutInflater.from(context);
    }

    public void setEntries(List<TranscriptEntry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public TranscriptEntry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return entries.get(position).id;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.history_list_item, parent, false);
            holder = new ViewHolder();
            holder.text = convertView.findViewById(R.id.history_text);
            holder.timestamp = convertView.findViewById(R.id.history_timestamp);
            holder.charCount = convertView.findViewById(R.id.history_char_count);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        TranscriptEntry entry = getItem(position);
        holder.text.setText(entry.text);
        holder.timestamp.setText(DateUtils.getRelativeTimeSpanString(
                entry.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        holder.charCount.setText(entry.charCount + " chars");

        return convertView;
    }

    private static class ViewHolder {
        TextView text;
        TextView timestamp;
        TextView charCount;
    }
}
