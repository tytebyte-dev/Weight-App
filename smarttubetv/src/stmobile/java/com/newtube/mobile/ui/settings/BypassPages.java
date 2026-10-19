package com.newtube.mobile.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.bypass.BypassController;
import com.newtube.mobile.bypass.BypassSettings;
import com.newtube.mobile.bypass.ProfileStore;
import com.newtube.mobile.bypass.ProxyProfile;
import com.newtube.mobile.bypass.XrayConfigBuilder;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Settings > Censorship bypass: the switch and its status, when and for whom it runs, the server
 * list (links, subscriptions, pasted Xray configs, the built-in server-less profile) and the
 * advanced routing / DNS / DPI options. Pages are built on every refresh and the settings search
 * crawls them, so building does no I/O.
 */
final class BypassPages {
    private BypassPages() {
    }

    // ---------------------------------------------------------------------------------------------
    // Main page
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page main(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        BypassController controller = BypassController.instance(context);
        BypassSettings settings = BypassSettings.instance(context);
        ProfileStore store = ProfileStore.instance(context);

        rows.add(SettingsRow.toggle(context.getString(R.string.bypass_switch), null,
                () -> controller.state() == BypassController.State.RUNNING || controller.state() == BypassController.State.STARTING,
                on -> {
                    if (on) {
                        controller.connect(context);
                    } else {
                        controller.disconnect(context);
                    }
                }).summary(() -> statusLine(context, controller)));

        rows.add(SettingsRow.header(context.getString(R.string.bypass_group_when)));
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.bypass_policy))
                .option(context.getString(R.string.bypass_policy_auto), context.getString(R.string.bypass_policy_auto_desc),
                        BypassSettings.POLICY_AUTO)
                .option(context.getString(R.string.bypass_policy_always), context.getString(R.string.bypass_policy_always_desc),
                        BypassSettings.POLICY_ALWAYS)
                .option(context.getString(R.string.bypass_policy_off), context.getString(R.string.bypass_policy_off_desc),
                        BypassSettings.POLICY_OFF)
                .bind(settings::policy, settings::setPolicy));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_regions),
                settings.regions().replace(",", ", "),
                page -> promptText(page, R.string.bypass_regions, R.string.bypass_regions_hint, settings.regions(), false,
                        value -> settings.setRegions(value.trim()))));
        rows.add(SettingsRow.toggle(context.getString(R.string.bypass_probe),
                context.getString(R.string.bypass_probe_summary), settings::probeDirect, settings::setProbeDirect));

        rows.add(SettingsRow.page(context.getString(R.string.bypass_profiles),
                () -> store.selected(settings).name, SettingsPages.BYPASS_PROFILES));
        rows.add(SettingsRow.page(context.getString(R.string.bypass_advanced),
                () -> context.getString(R.string.bypass_advanced_summary), SettingsPages.BYPASS_ADVANCED));

        rows.add(SettingsRow.note(context.getString(R.string.bypass_note)));
        return new SettingsPages.Page(context.getString(R.string.bypass_title), rows);
    }

    private static CharSequence statusLine(Context context, BypassController c) {
        switch (c.state()) {
            case RUNNING:
                return context.getString(R.string.bypass_status_running_local, c.detail());
            case STARTING:
                return context.getString(R.string.bypass_status_starting);
            case ERROR:
                return context.getString(R.string.bypass_status_error, c.detail());
            default:
                return context.getString(R.string.bypass_status_off) + " · " + c.coreStatus();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Profiles
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page profiles(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        BypassController controller = BypassController.instance(context);
        BypassSettings settings = BypassSettings.instance(context);
        ProfileStore store = ProfileStore.instance(context);

        rows.add(SettingsRow.action(context.getString(R.string.bypass_add_clipboard),
                context.getString(R.string.bypass_add_clipboard_summary), page -> addFromClipboard(page, controller)));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_add_paste),
                context.getString(R.string.bypass_add_paste_summary),
                page -> promptText(page, R.string.bypass_add_paste, R.string.bypass_add_paste_hint, "", true,
                        text -> addFromText(page, controller, text))));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_add_subscription),
                context.getString(R.string.bypass_add_subscription_summary),
                page -> promptText(page, R.string.bypass_add_subscription, R.string.bypass_subscription_hint, "https://", false,
                        url -> addSubscription(page, controller, url))));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_ping_all), null, page -> {
            controller.pingAll(page::rebuild);
            MobileSnackbar.show(context, R.string.bypass_pinging);
        }));

        rows.add(SettingsRow.header(context.getString(R.string.bypass_servers)));
        String selectedId = store.selected(settings).id;
        for (ProxyProfile p : store.all()) {
            boolean selected = p.id.equals(selectedId);
            rows.add(SettingsRow.action((selected ? "✓ " : "") + p.name, profileSummary(context, controller, p),
                    page -> profileMenu(page, controller, settings, store, p)));
        }

        List<ProfileStore.Subscription> subs = store.subscriptions();
        if (!subs.isEmpty()) {
            rows.add(SettingsRow.header(context.getString(R.string.bypass_subscriptions)));
            for (ProfileStore.Subscription s : subs) {
                String when = s.updatedAt == 0 ? context.getString(R.string.bypass_never)
                        : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(s.updatedAt));
                rows.add(SettingsRow.action(s.name, context.getString(R.string.bypass_subscription_summary, s.count, when),
                        page -> subscriptionMenu(page, controller, store, s)));
            }
        }
        return new SettingsPages.Page(context.getString(R.string.bypass_profiles), rows);
    }

    private static CharSequence profileSummary(Context context, BypassController controller, ProxyProfile p) {
        if (p.isBuiltin()) {
            return context.getString(R.string.bypass_profile_builtin_summary);
        }
        if (p.isCustom()) {
            return context.getString(R.string.bypass_profile_custom_summary);
        }
        Long ping = controller.ping(p.id);
        String pingText = ping == null ? "" : ping < 0 ? " · " + context.getString(R.string.bypass_ping_failed) : " · " + ping + " ms";
        return p.protocol + " · " + p.host + ":" + p.port + pingText;
    }

    private static void profileMenu(SettingsPageFragment page, BypassController controller, BypassSettings settings,
                                    ProfileStore store, ProxyProfile p) {
        Context context = page.requireContext();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(context.getString(R.string.bypass_use));
        actions.add(() -> {
            settings.setSelectedProfileId(p.id);
            controller.reconnectIfRunning(context);
            page.rebuild();
        });
        if (p.link != null) {
            labels.add(context.getString(R.string.bypass_copy_link));
            actions.add(() -> {
                copy(context, p.link);
                MobileSnackbar.show(context, R.string.bypass_copied);
            });
        }
        if (!p.isBuiltin()) {
            labels.add(context.getString(R.string.bypass_rename));
            actions.add(() -> promptText(page, R.string.bypass_rename, R.string.bypass_name_hint, p.name, false, name -> {
                if (!name.trim().isEmpty()) {
                    store.rename(p.id, name.trim());
                    page.rebuild();
                }
            }));
            labels.add(context.getString(R.string.bypass_delete));
            actions.add(() -> {
                if (p.id.equals(settings.selectedProfileId())) {
                    settings.setSelectedProfileId(ProxyProfile.BUILTIN_ID);
                    controller.reconnectIfRunning(context);
                }
                store.remove(p.id);
                page.rebuild();
            });
        }
        page.showDialog(new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog)
                .setTitle(p.name)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static void subscriptionMenu(SettingsPageFragment page, BypassController controller, ProfileStore store,
                                         ProfileStore.Subscription s) {
        Context context = page.requireContext();
        CharSequence[] labels = {context.getString(R.string.bypass_sub_update), context.getString(R.string.bypass_sub_delete)};
        page.showDialog(new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog)
                .setTitle(s.name)
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        MobileSnackbar.show(context, R.string.bypass_updating);
                        controller.refreshSubscription(s, (n, error) -> {
                            MobileSnackbar.show(context, error != null ? context.getString(R.string.bypass_failed, error)
                                    : context.getString(R.string.bypass_added_servers, n), null, null);
                            page.rebuild();
                        });
                    } else {
                        store.removeSubscription(s.id);
                        page.rebuild();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static void addFromClipboard(SettingsPageFragment page, BypassController controller) {
        Context context = page.requireContext();
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence text = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            text = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
        }
        if (text == null || text.toString().trim().isEmpty()) {
            MobileSnackbar.show(context, R.string.bypass_clipboard_empty);
            return;
        }
        addFromText(page, controller, text.toString());
    }

    private static void addFromText(SettingsPageFragment page, BypassController controller, String text) {
        Context context = page.requireContext();
        BypassController.ImportResult r = controller.importText(text);
        if (r.subscriptionUrl != null) {
            addSubscription(page, controller, r.subscriptionUrl);
            return;
        }
        if (r.added > 0) {
            String msg = context.getString(R.string.bypass_added_servers, r.added)
                    + (r.skipped > 0 ? " · " + context.getString(R.string.bypass_skipped, r.skipped) : "");
            MobileSnackbar.show(context, msg, null, null);
        } else {
            MobileSnackbar.show(context, context.getString(R.string.bypass_failed, r.error != null ? r.error
                    : context.getString(R.string.bypass_import_unknown)), null, null);
        }
        page.rebuild();
    }

    private static void addSubscription(SettingsPageFragment page, BypassController controller, String url) {
        Context context = page.requireContext();
        String u = url.trim();
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            MobileSnackbar.show(context, R.string.bypass_bad_url);
            return;
        }
        String host = u.replaceFirst("^https?://", "").split("[/?#]")[0];
        MobileSnackbar.show(context, R.string.bypass_updating);
        controller.addSubscription(host, u, (n, error) -> {
            MobileSnackbar.show(context, error != null ? context.getString(R.string.bypass_failed, error)
                    : context.getString(R.string.bypass_added_servers, n), null, null);
            page.rebuild();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Advanced
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page advanced(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        BypassSettings s = BypassSettings.instance(context);
        BypassController controller = BypassController.instance(context);

        rows.add(SettingsRow.header(context.getString(R.string.bypass_group_routing)));
        rows.add(SettingsRow.toggle(context.getString(R.string.bypass_route_all),
                context.getString(R.string.bypass_route_all_summary), s::routeAll, on -> {
                    s.setRouteAll(on);
                    controller.reconnectIfRunning(context);
                }));
        rows.add(SettingsRow.toggle(context.getString(R.string.bypass_sniffing),
                context.getString(R.string.bypass_sniffing_summary), s::sniffing, on -> {
                    s.setSniffing(on);
                    controller.reconnectIfRunning(context);
                }));
        rows.add(SettingsRow.toggle(context.getString(R.string.bypass_ipv6),
                context.getString(R.string.bypass_ipv6_summary), s::ipv6, on -> {
                    s.setIpv6(on);
                    controller.reconnectIfRunning(context);
                }));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_direct_domains),
                summaryOf(context, s.directDomainsText()),
                page -> promptText(page, R.string.bypass_direct_domains, R.string.bypass_domains_hint, s.directDomainsText(), true,
                        v -> {
                            s.setDirectDomainsText(v.trim());
                            controller.reconnectIfRunning(context);
                        })));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_proxy_domains),
                summaryOf(context, s.proxyDomainsText()),
                page -> promptText(page, R.string.bypass_proxy_domains, R.string.bypass_domains_hint, s.proxyDomainsText(), true,
                        v -> {
                            s.setProxyDomainsText(v.trim());
                            controller.reconnectIfRunning(context);
                        })));

        rows.add(SettingsRow.header(context.getString(R.string.bypass_group_dpi)));
        rows.add(SettingsRow.<String>choice(context.getString(R.string.bypass_fragment_packets))
                .option(context.getString(R.string.bypass_fragment_tlshello), "tlshello")
                .option(context.getString(R.string.bypass_fragment_first3), "1-3")
                .option(context.getString(R.string.bypass_fragment_all), "")
                .bind(s::fragmentPackets, v -> {
                    s.setFragmentPackets(v);
                    controller.reconnectIfRunning(context);
                }));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_fragment_length), s.fragmentLength() + " B",
                page -> promptRange(page, controller, R.string.bypass_fragment_length, s.fragmentLength(), s::setFragmentLength)));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_fragment_interval), s.fragmentInterval() + " ms",
                page -> promptRange(page, controller, R.string.bypass_fragment_interval, s.fragmentInterval(), s::setFragmentInterval)));
        rows.add(SettingsRow.toggle(context.getString(R.string.bypass_fragment_proxy),
                context.getString(R.string.bypass_fragment_proxy_summary), s::fragmentProxyConnection, on -> {
                    s.setFragmentProxyConnection(on);
                    controller.reconnectIfRunning(context);
                }));

        rows.add(SettingsRow.header(context.getString(R.string.bypass_group_dns)));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_dns),
                s.dnsServersText().isEmpty() ? context.getString(R.string.bypass_dns_default) : s.dnsServersText().replace("\n", ", "),
                page -> promptText(page, R.string.bypass_dns, R.string.bypass_dns_hint, s.dnsServersText(), true, v -> {
                    s.setDnsServersText(v.trim());
                    controller.reconnectIfRunning(context);
                })));

        rows.add(SettingsRow.header(context.getString(R.string.bypass_group_misc)));
        rows.add(SettingsRow.toggle(context.getString(R.string.bypass_sub_auto),
                context.getString(R.string.bypass_sub_auto_summary), s::subscriptionsAutoUpdate, s::setSubscriptionsAutoUpdate));
        rows.add(SettingsRow.<String>choice(context.getString(R.string.bypass_log_level))
                .option("none", "none").option("error", "error").option("warning", "warning")
                .option("info", "info").option("debug", "debug")
                .bind(s::logLevel, v -> {
                    s.setLogLevel(v);
                    controller.reconnectIfRunning(context);
                }));
        rows.add(SettingsRow.action(context.getString(R.string.bypass_copy_config),
                context.getString(R.string.bypass_copy_config_summary), page -> copyConfig(page, s)));
        return new SettingsPages.Page(context.getString(R.string.bypass_advanced), rows);
    }

    private static CharSequence summaryOf(Context context, String text) {
        return text.isEmpty() ? context.getString(R.string.bypass_none) : text.replace("\n", ", ");
    }

    private static void promptRange(SettingsPageFragment page, BypassController controller, int titleRes, String current,
                                    java.util.function.Consumer<String> save) {
        Context context = page.requireContext();
        promptText(page, titleRes, R.string.bypass_range_hint, current, false, v -> {
            String t = v.trim();
            if (BypassSettings.isRange(t) && !t.equals("0")) {
                save.accept(t);
                controller.reconnectIfRunning(context);
                page.rebuild();
            } else {
                MobileSnackbar.show(context, R.string.bypass_range_invalid);
            }
        });
    }

    private static void copyConfig(SettingsPageFragment page, BypassSettings s) {
        Context context = page.requireContext();
        try {
            ProxyProfile p = ProfileStore.instance(context).selected(s);
            XrayConfigBuilder.Options o = s.toOptions();
            o.socksUser = "user";
            o.socksPass = "password"; // placeholders: the real credentials are random per start
            copy(context, new org.json.JSONObject(XrayConfigBuilder.build(p, o)).toString(2));
            MobileSnackbar.show(context, R.string.bypass_copied);
        } catch (Exception e) {
            MobileSnackbar.show(context, context.getString(R.string.bypass_failed, e.getMessage()), null, null);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void copy(Context context, String text) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("bypass", text));
        }
    }

    /** A text box in a dialog; the page rebuilds after OK so summaries show the new value. */
    private static void promptText(@NonNull SettingsPageFragment page, int titleRes, int hintRes, @Nullable String initial,
                                   boolean multiline, @NonNull java.util.function.Consumer<String> onOk) {
        Context context = page.requireContext();
        EditText input = new EditText(context);
        input.setHint(hintRes);
        input.setText(initial != null ? initial : "");
        input.setSelection(input.getText().length());
        input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (multiline) {
            input.setMinLines(3);
            input.setMaxLines(8);
            input.setHorizontallyScrolling(false);
        } else {
            input.setSingleLine(true);
        }
        int pad = Math.round(context.getResources().getDisplayMetrics().density * 20);
        LinearLayout box = new LinearLayout(context);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.showDialog(new MaterialAlertDialogBuilder(context, R.style.MobileAlertDialog)
                .setTitle(titleRes)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    onOk.accept(input.getText().toString());
                    page.rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
