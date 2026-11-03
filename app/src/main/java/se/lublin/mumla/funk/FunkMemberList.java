/*
 * Copyright (C) 2026 Mumla Funk contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.funk;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.List;

import se.lublin.mumla.R;

/**
 * Fills a container with one row per person ({@code funk_member_row}): the display of the
 * walkie-talkie screen and the list in the popup. Both are dark. Rows are reused, the state is
 * published several times per second while someone talks.
 */
final class FunkMemberList {
    private FunkMemberList() {
    }

    /** @return the number of rows now in the container. */
    static int fill(@NonNull ViewGroup container, @NonNull List<FunkState.Member> members) {
        Context context = container.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        while (container.getChildCount() > members.size()) {
            container.removeViewAt(container.getChildCount() - 1);
        }
        while (container.getChildCount() < members.size()) {
            container.addView(inflater.inflate(R.layout.funk_member_row, container, false));
        }
        for (int i = 0; i < members.size(); i++) {
            bind(container.getChildAt(i), members.get(i));
        }
        return members.size();
    }

    private static void bind(View row, FunkState.Member m) {
        Context context = row.getContext();
        ImageView dot = row.findViewById(R.id.member_dot);
        TextView name = row.findViewById(R.id.member_name);
        TextView channel = row.findViewById(R.id.member_channel);
        dot.setImageResource(m.talking ? R.drawable.funk_dot_talk
                : m.sameChannel ? R.drawable.funk_dot_live : R.drawable.funk_dot_idle);
        name.setText(m.self ? context.getString(R.string.funk_you, m.name) : m.name);
        name.setTypeface(null, m.talking ? Typeface.BOLD : Typeface.NORMAL);
        channel.setText(m.sameChannel || m.channel == null ? "" : m.channel);
        row.findViewById(R.id.member_muted).setVisibility(m.muted ? View.VISIBLE : View.GONE);
        row.setBackgroundResource(m.talking ? R.drawable.bg_funk_member_talking : 0);

        StringBuilder description = new StringBuilder(name.getText());
        if (m.talking) {
            description.append(", ").append(context.getString(R.string.funk_member_talking));
        }
        if (m.muted) {
            description.append(", ").append(context.getString(R.string.funk_member_muted));
        }
        if (channel.length() > 0) {
            description.append(", ").append(channel.getText());
        }
        row.setContentDescription(description);
    }
}
