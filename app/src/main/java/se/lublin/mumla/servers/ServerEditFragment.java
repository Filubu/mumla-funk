/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.servers;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.funk.FunkPrefs;

public class ServerEditFragment extends DialogFragment {
    private static final String ARGUMENT_SERVER = "server";
    private static final String ARGUMENT_ACTION = "action";
    private static final String ARGUMENT_IGNORE_TITLE = "ignore_title";

    private EditText mNameEdit;
    private EditText mHostEdit;
    private EditText mPortEdit;
    private EditText mUsernameEdit;
    private EditText mPasswordEdit;
    private TextInputLayout mHostLayout;
    private TextInputLayout mPortLayout;
    private TextInputLayout mUsernameLayout;
    private MaterialSwitch mFunkSwitch;

    private ServerEditListener mListener;

    /**
     * Creates a new {@link ServerEditFragment} dialog. Results will be delivered to the parent
     * activity via {@link ServerEditListener}.
     * @param server Optional, if set will populate the fragment with data from the server.
     * @param action The action the fragment is performing (i.e. Add, Edit)
     * @param ignoreTitle If true, don't show fields related to the server title (useful for quick
     *                    connect dialogs)
     */
    public static DialogFragment createServerEditDialog(Context context, Server server,
                                                        Action action,
                                                        boolean ignoreTitle) {
        Bundle args = new Bundle();
        args.putParcelable(ARGUMENT_SERVER, server);
        args.putInt(ARGUMENT_ACTION, action.ordinal());
        args.putBoolean(ARGUMENT_IGNORE_TITLE, ignoreTitle);
        return (DialogFragment) Fragment.instantiate(context, ServerEditFragment.class.getName(), args);
    }

    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (ServerEditListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity + " must implement ServerEditListener!");
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        // Override positive button to not automatically dismiss on press.
        // We can't accomplish this with AlertDialog.Builder.
        ((AlertDialog) requireDialog()).getButton(Dialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (validate()) {
                Server server = createServer();
                mListener.onServerEdited(getAction(), server, mFunkSwitch.isChecked());
                dismiss();
            }
        });
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Settings settings = Settings.getInstance(requireContext());

        int title;
        String actionName;
        switch (getAction()) {
            case ADD_ACTION:
                title = R.string.server_edit_title_add;
                actionName = getString(R.string.add);
                break;
            case EDIT_ACTION:
                title = R.string.server_edit_title_edit;
                actionName = getString(R.string.server_edit_save);
                break;
            case CONNECT_ACTION:
                title = R.string.connectToServer;
                actionName = getString(R.string.connect);
                break;
            default:
                throw new RuntimeException("Unknown action " + getAction());
        }

        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_server_edit, null, false);

        mNameEdit = view.findViewById(R.id.server_edit_name);
        mHostEdit = view.findViewById(R.id.server_edit_host);
        mPortEdit = view.findViewById(R.id.server_edit_port);
        mUsernameEdit = view.findViewById(R.id.server_edit_username);
        mPasswordEdit = view.findViewById(R.id.server_edit_password);
        mHostLayout = view.findViewById(R.id.server_edit_host_layout);
        mPortLayout = view.findViewById(R.id.server_edit_port_layout);
        mUsernameLayout = view.findViewById(R.id.server_edit_username_layout);
        mFunkSwitch = view.findViewById(R.id.server_edit_funk);
        clearErrorOnEdit(mHostEdit, mHostLayout);
        clearErrorOnEdit(mPortEdit, mPortLayout);
        clearErrorOnEdit(mUsernameEdit, mUsernameLayout);

        Server oldServer = getServer();
        if (oldServer != null) {
            // Server.getName() falls back to the host; only show a name that was really set.
            String name = oldServer.getName();
            mNameEdit.setText(name != null && !name.equals(oldServer.getHost()) ? name : "");
            mHostEdit.setText(oldServer.getHost());
            if (oldServer.getPort() != 0) {
                mPortEdit.setText(String.valueOf(oldServer.getPort()));
            }
            mUsernameEdit.setText(oldServer.getUsername());
            mPasswordEdit.setText(oldServer.getPassword());
        }
        if (mUsernameEdit.getText().length() == 0) {
            // Prefill a name the user chose in the settings; never the generic default.
            String defaultUsername = settings.getDefaultUsername();
            if (!Settings.DEFAULT_DEFAULT_USERNAME.equals(defaultUsername)) {
                mUsernameEdit.setText(defaultUsername);
            }
        }

        boolean funk = oldServer != null && oldServer.isSaved()
                ? FunkPrefs.get(requireContext()).isFunkServer(oldServer.getId())
                : FunkPrefs.DEFAULT_SERVER_FUNK;
        mFunkSwitch.setChecked(funk);
        view.findViewById(R.id.server_edit_funk_summary).setOnClickListener(v -> mFunkSwitch.toggle());

        if (shouldIgnoreTitle()) {
            // Quick connect: the server is not saved, so neither a name nor the walkie-talkie
            // setting would be kept.
            view.findViewById(R.id.server_edit_name_layout).setVisibility(View.GONE);
            view.findViewById(R.id.server_edit_funk_row).setVisibility(View.GONE);
            mFunkSwitch.setChecked(false);
        }

        return new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(title)
                .setPositiveButton(actionName, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setView(view)
                .create();
    }

    private static void clearErrorOnEdit(EditText edit, TextInputLayout layout) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                layout.setError(null);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    public Server createServer() {
        String name = mNameEdit.getText().toString().trim();
        String host = mHostEdit.getText().toString().trim();

        int port;
        try {
            port = Integer.parseInt(mPortEdit.getText().toString().trim());
        } catch (final NumberFormatException ex) {
            // Setting 0, meaning that port isn't configured. Consumers of
            // Server.getPort() will have to deal with that. Like displaying
            // nothing, looking up SRV record, using Constants.DEFAULT_PORT.
            port = 0;
        }

        String username = mUsernameEdit.getText().toString().trim();
        String password = mPasswordEdit.getText().toString();

        // Inherit database ID of provided server.
        long id;
        if (getServer() != null) {
            id = getServer().getId();
        } else {
            id = -1;
        }

        return new Server(id, name, host, port, username, password);
    }

    /**
     * Checks all fields in this ServerEditFragment for validity.
     * If an invalid field is found, an error is shown and false is returned.
     * @return true if the inputted values are valid, false otherwise.
     */
    public boolean validate() {
        boolean valid = true;
        String host = mHostEdit.getText().toString().trim();
        if (host.isEmpty() || host.contains(" ") || host.contains("/")) {
            // Typical mistakes: a full URL ("mumble://host/") or a typo with spaces.
            mHostLayout.setError(getString(R.string.server_edit_host_invalid));
            valid = false;
        }
        String portText = mPortEdit.getText().toString().trim();
        if (!portText.isEmpty()) {
            try {
                int port = Integer.parseInt(portText);
                if (port < 1 || port > 65535) {
                    mPortLayout.setError(getString(R.string.invalid_port_range));
                    valid = false;
                }
            } catch (NumberFormatException nfe) {
                mPortLayout.setError(getString(R.string.invalid_port_range));
                valid = false;
            }
        }
        if (mUsernameEdit.getText().toString().trim().isEmpty()) {
            mUsernameLayout.setError(getString(R.string.server_edit_username_missing));
            valid = false;
        }
        return valid;
    }

    @Nullable
    private Server getServer() {
        return requireArguments().getParcelable(ARGUMENT_SERVER);
    }

    private Action getAction() {
        return Action.values()[requireArguments().getInt(ARGUMENT_ACTION)];
    }

    private boolean shouldIgnoreTitle() {
        return requireArguments().getBoolean(ARGUMENT_IGNORE_TITLE);
    }

    public interface ServerEditListener {
        /**
         * @param funk Mumla Funk: whether the server uses the walkie-talkie UI (saved servers).
         */
        void onServerEdited(Action action, Server server, boolean funk);
    }

    public enum Action {
        CONNECT_ACTION,
        EDIT_ACTION,
        ADD_ACTION
    }
}
