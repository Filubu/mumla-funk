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
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

import se.lublin.mumla.funk.FunkPrefs;

/** Common base of both walkie-talkie widgets. State updates are pushed by the service. */
abstract class FunkWidgetProvider extends AppWidgetProvider {
    abstract boolean isLarge();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            FunkWidgets.update(context, manager, id, isLarge());
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId,
                                          Bundle newOptions) {
        FunkWidgets.update(context, manager, appWidgetId, isLarge());
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        FunkPrefs prefs = FunkPrefs.get(context);
        for (int id : appWidgetIds) {
            prefs.removeWidget(id);
        }
    }
}
