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

package se.lublin.mumla.funk.widget;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.funk.FunkPrefs;
import se.lublin.mumla.funk.FunkServers;

/**
 * Asks which saved server a walkie-talkie widget should use. Shown when the widget is placed,
 * and again via the launcher's "reconfigure" option.
 */
public class FunkWidgetConfigActivity extends AppCompatActivity {
    private int mAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // If the user backs out, the launcher must not place the widget.
        setResult(RESULT_CANCELED);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            mAppWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (mAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_funk_widget_config);
        View root = findViewById(R.id.config_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        root.setOnClickListener(v -> finish());
        findViewById(R.id.config_cancel).setOnClickListener(v -> finish());
        findViewById(R.id.config_open_app).setOnClickListener(v -> {
            Intent intent = new Intent(this, MumlaActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            fillList();
        }
    }

    private void fillList() {
        ViewGroup list = findViewById(R.id.config_list);
        list.removeAllViews();
        List<Server> servers = FunkServers.loadAll(this);
        findViewById(R.id.config_empty).setVisibility(servers.isEmpty() ? View.VISIBLE : View.GONE);
        long current = FunkPrefs.get(this).getWidgetServerId(mAppWidgetId);
        LayoutInflater inflater = getLayoutInflater();
        for (Server server : servers) {
            View row = inflater.inflate(R.layout.funk_server_choice_row, list, false);
            ((TextView) row.findViewById(R.id.choice_name)).setText(FunkServers.displayName(server));
            ((TextView) row.findViewById(R.id.choice_host)).setText(
                    server.getUsername() + " @ " + server.getHost()
                            + (server.getPort() > 0 && server.getPort() != 64738 ? ":" + server.getPort() : ""));
            row.findViewById(R.id.choice_check).setVisibility(
                    server.getId() == current ? View.VISIBLE : View.GONE);
            row.setOnClickListener(v -> choose(server));
            list.addView(row);
        }
    }

    private void choose(Server server) {
        FunkPrefs.get(this).setWidgetServer(mAppWidgetId, server.getId(),
                FunkServers.displayName(server));
        FunkWidgets.updateAll(this);
        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }
}
