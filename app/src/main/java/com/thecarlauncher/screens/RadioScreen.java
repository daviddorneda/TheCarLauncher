package com.thecarlauncher.screens;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.thecarlauncher.LauncherActivity;
import com.thecarlauncher.R;
import com.thecarlauncher.mib.Mib;
import com.thecarlauncher.mib.MibBar;
import com.thecarlauncher.mib.MibPager;
import com.thecarlauncher.radio.FmScaleView;
import com.thecarlauncher.radio.InternetRadioController;
import com.thecarlauncher.radio.RadioBrowserApi;
import com.thecarlauncher.radio.RadioController;
import com.thecarlauncher.radio.RadioLogos;
import com.thecarlauncher.radio.RadioStation;
import com.thecarlauncher.util.Prefs;
import com.thecarlauncher.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * MIB "Radio" screen supporting both built-in MCU FM radio and Internet Radio.
 */
public class RadioScreen extends Screen implements RadioController.Listener,
        InternetRadioController.Listener, RadioLogos.Listener {

    private static final int PER_PAGE = 6;
    private static final String P_RADIO_MODE = "radio_mode";

    private RadioController fmRadio;
    private InternetRadioController netRadio;

    private boolean internetMode;
    private boolean manualFm;

    private TextView freqText, unitText, infoText, searchOverlayTitle;
    private View presetsArea, manualFmArea, fmListArea, mainView, internetControlRow;
    private ImageView internetPlayBtn, internetStationLogo;
    private MibPager pager;
    private LinearLayout dots;
    private FmScaleView fmScale;
    private ListView fmListView;

    private View searchOverlay;
    private ListView searchListView;
    private final SearchResultsAdapter searchAdapter = new SearchResultsAdapter();
    private final List<RadioStation> searchResults = new ArrayList<>();

    private MibBar.Key modeKey, fmListKey, fmManualKey, searchKey, addUrlKey;
    private final PresetTile[] presets = new PresetTile[RadioController.PRESETS];
    private final FmStationAdapter fmAdapter = new FmStationAdapter();

    public RadioScreen(LauncherActivity act) {
        super(act);
    }

    @Override
    public String title() {
        return "Radio";
    }

    private String s(String sr, String en) {
        return Mib.t(act, sr, en);
    }

    private String s(String sr, String en, String es) {
        return Mib.t(act, sr, en, es);
    }

    @Override
    protected View create(ViewGroup parent) {
        fmRadio = RadioController.get(act);
        netRadio = InternetRadioController.get(act);

        boolean fmAvail = fmRadio.available();
        String savedMode = Prefs.str(act, P_RADIO_MODE, fmAvail ? "fm" : "internet");
        internetMode = !fmAvail || "internet".equals(savedMode);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);

        FrameLayout content = new FrameLayout(act);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- Main view: frequency/station row + presets / scale ----
        FrameLayout main = new FrameLayout(act);
        mainView = main;
        content.addView(main, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int arrowSize = Mib.px(act, 76), sideMargin = Mib.px(act, 18), topMargin = Mib.px(act, 14);

        // FM Left/Right seek arrows (or Internet station change)
        main.addView(arrowBox(R.drawable.ui_key_prev, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (internetMode) {
                    stepInternetPreset(-1);
                } else if (manualFm) {
                    fmRadio.stepDown();
                } else {
                    fmRadio.seekDown();
                }
            }
        }), boxParams(arrowSize, sideMargin, topMargin, Gravity.LEFT));

        main.addView(arrowBox(R.drawable.ui_key_next, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (internetMode) {
                    stepInternetPreset(1);
                } else if (manualFm) {
                    fmRadio.stepUp();
                } else {
                    fmRadio.seekUp();
                }
            }
        }), boxParams(arrowSize, sideMargin, topMargin, Gravity.RIGHT));

        // Center header: Station frequency or name
        LinearLayout centerHeader = new LinearLayout(act);
        centerHeader.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        freqText = Mib.text(act, "87.5", 46, Mib.regular(act), Mib.WHITE);
        unitText = Mib.text(act, " MHz", 28, Mib.semibold(act), Mib.WHITE);
        unitText.setPadding(0, 0, 0, Mib.px(act, 6));
        centerHeader.addView(freqText);
        centerHeader.addView(unitText);

        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, arrowSize);
        fl.topMargin = topMargin;
        fl.leftMargin = fl.rightMargin = arrowSize + sideMargin * 2;
        centerHeader.setGravity(Gravity.CENTER);
        main.addView(centerHeader, fl);

        // Info text under frequency/station name
        infoText = Mib.text(act, "", 24, Mib.regular(act), Mib.GREY);
        infoText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        il.topMargin = topMargin + arrowSize + Mib.px(act, 6);
        il.leftMargin = il.rightMargin = arrowSize + sideMargin * 2;
        main.addView(infoText, il);

        // Internet Radio Play/Pause row (shown in Internet mode)
        LinearLayout netRow = new LinearLayout(act);
        netRow.setGravity(Gravity.CENTER);
        netRow.setOrientation(LinearLayout.HORIZONTAL);

        internetPlayBtn = new ImageView(act);
        internetPlayBtn.setImageResource(R.drawable.ui_ic_radio);
        internetPlayBtn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        netRow.addView(internetPlayBtn, new LinearLayout.LayoutParams(Mib.px(act, 48), Mib.px(act, 48)));
        netRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (internetMode) {
                    netRadio.toggle();
                }
            }
        });
        internetControlRow = netRow;

        FrameLayout.LayoutParams netLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        netLp.topMargin = topMargin + arrowSize + Mib.px(act, 38);
        main.addView(netRow, netLp);

        // Presets: three pages of six
        FrameLayout pa = new FrameLayout(act);
        presetsArea = pa;
        pager = new MibPager(act);

        for (int p = 0; p < RadioController.PRESETS / PER_PAGE; p++) {
            LinearLayout row = new LinearLayout(act);
            row.setPadding(Mib.px(act, 14), 0, Mib.px(act, 14), 0);
            for (int i = 0; i < PER_PAGE; i++) {
                PresetTile t = new PresetTile(p * PER_PAGE + i);
                presets[p * PER_PAGE + i] = t;
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                tl.leftMargin = tl.rightMargin = Mib.px(act, 2);
                row.addView(t, tl);
            }
            pager.addView(row);
        }

        FrameLayout.LayoutParams pgl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Mib.px(act, 160));
        pa.addView(pager, pgl);

        dots = new LinearLayout(act);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < pager.getChildCount(); i++) {
            View d = new View(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Mib.px(act, 13), Mib.px(act, 13));
            lp.leftMargin = lp.rightMargin = Mib.px(act, 5);
            dots.addView(d, lp);
        }

        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Mib.px(act, 20), Gravity.BOTTOM);
        pa.addView(dots, dl);

        pager.setListener(new MibPager.Listener() {
            @Override
            public void onPage(int page) {
                updateDots(page);
            }
        });
        updateDots(0);

        FrameLayout.LayoutParams pal = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Mib.px(act, 222), Gravity.BOTTOM);
        pal.bottomMargin = Mib.px(act, 22);
        main.addView(pa, pal);

        // Manual tuning FM scale view
        fmScale = new FmScaleView(act);
        fmScale.setPadding(Mib.px(act, 30), 0, Mib.px(act, 30), 0);
        fmScale.setListener(new FmScaleView.Listener() {
            @Override
            public void onTune(int f, boolean done) {
                if (!internetMode) {
                    freqText.setText(RadioController.format(f));
                    if (done) fmRadio.tune(f);
                }
            }
        });
        manualFmArea = fmScale;
        fmScale.setVisibility(View.GONE);

        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Mib.px(act, 150), Gravity.CENTER_VERTICAL);
        sl.topMargin = Mib.px(act, 50);
        main.addView(fmScale, sl);

        // FM Station List Area
        LinearLayout la = new LinearLayout(act);
        la.setOrientation(LinearLayout.VERTICAL);
        la.setPadding(Mib.px(act, 40), Mib.px(act, 10), Mib.px(act, 40), 0);
        la.setBackgroundColor(0xFF000000);
        la.setVisibility(View.GONE);
        fmListArea = la;

        TextView listTitle = Mib.text(act, s("Lista stanica", "Station list"), 26, Mib.semibold(act), Mib.WHITE);
        listTitle.setPadding(Mib.px(act, 6), 0, 0, Mib.px(act, 8));
        la.addView(listTitle);

        View rule = new View(act);
        rule.setBackgroundColor(Mib.BLUE);
        la.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, Mib.px(act, 2))));

        fmListView = new ListView(act);
        fmListView.setDivider(new android.graphics.drawable.ColorDrawable(0xFF55565F));
        fmListView.setDividerHeight(Math.max(1, Mib.px(act, 1)));
        fmListView.setSelector(Mib.keyPressed());
        fmListView.setAdapter(fmAdapter);
        fmListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int position, long id) {
                if (position == 0) {
                    if (fmRadio.scanning()) fmRadio.stopScan();
                    else fmRadio.startScan();
                } else {
                    fmRadio.tune(fmRadio.stations().get(position - 1).freq);
                }
            }
        });
        la.addView(fmListView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(la, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Online Radio Search Overlay Panel
        setupSearchOverlay(content);

        // ---- Bottom MibBar Soft Keys ----
        MibBar bar = new MibBar(act);

        modeKey = bar.add(internetMode ? s("Internet Radio", "Internet Radio") : s("FM Radio", "FM Radio"),
                R.drawable.ui_ic_globe, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        setInternetMode(!internetMode);
                    }
                });

        searchKey = bar.add(s("Pretraga", "Search online", "Buscar online"), R.drawable.ui_ic_globe, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchOverlay(true);
            }
        });

        addUrlKey = bar.add(s("Dodaj URL", "Add URL", "Añadir URL"), R.drawable.ui_ic_note, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddUrlDialog();
            }
        });

        fmListKey = bar.add(s("Lista stan.", "Stations"), R.drawable.ui_key_list, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFmList(fmListArea.getVisibility() != View.VISIBLE);
            }
        });

        fmManualKey = bar.add(s("Ručno", "Manual"), R.drawable.ui_key_manual, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFmList(false);
                setManualFm(!manualFm);
            }
        });

        bar.gap(4);
        bar.add("Setup", R.drawable.ui_key_setup, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act.showSettings(SettingsScreen.PAGE_RADIO);
            }
        });

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, MibBar.height(act)));

        updateBarKeys();
        return root;
    }

    private void setupSearchOverlay(FrameLayout parent) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Mib.px(act, 30), Mib.px(act, 10), Mib.px(act, 30), Mib.px(act, 10));
        panel.setBackgroundColor(0xFA000000);
        panel.setVisibility(View.GONE);
        searchOverlay = panel;

        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        searchOverlayTitle = Mib.text(act, s("Internet Radio Pretraga", "Radio Browser Search", "Buscar emisoras online"),
                26, Mib.semibold(act), Mib.WHITE);
        header.addView(searchOverlayTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = Mib.text(act, "[ X ]", 26, Mib.bold(act), Mib.BLUE);
        closeBtn.setPadding(Mib.px(act, 16), 0, Mib.px(act, 16), 0);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSearchOverlay(false);
            }
        });
        header.addView(closeBtn);
        panel.addView(header);

        // Search Input Row
        LinearLayout inputRow = new LinearLayout(act);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, Mib.px(act, 10), 0, Mib.px(act, 10));

        final EditText queryBox = new EditText(act);
        queryBox.setHint(s("Pretraži emisije/žanrove…", "Search stations or tags…", "Buscar emisoras…"));
        queryBox.setHintTextColor(Mib.GREY);
        queryBox.setTextColor(Mib.WHITE);
        queryBox.setSingleLine(true);
        queryBox.setBackground(Mib.box(act));
        queryBox.setPadding(Mib.px(act, 14), Mib.px(act, 10), Mib.px(act, 14), Mib.px(act, 10));

        inputRow.addView(queryBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnSearch = Mib.text(act, s(" Traži ", " Search ", " Buscar "), 24, Mib.semibold(act), Mib.WHITE);
        btnSearch.setBackground(Mib.box(act));
        btnSearch.setPadding(Mib.px(act, 16), Mib.px(act, 10), Mib.px(act, 16), Mib.px(act, 10));
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performSearch(queryBox.getText().toString());
            }
        });
        inputRow.addView(btnSearch);
        panel.addView(inputRow);

        searchListView = new ListView(act);
        searchListView.setDivider(new android.graphics.drawable.ColorDrawable(0xFF44454F));
        searchListView.setDividerHeight(Math.max(1, Mib.px(act, 1)));
        searchListView.setSelector(Mib.keyPressed());
        searchListView.setAdapter(searchAdapter);
        searchListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int position, long id) {
                if (position >= 0 && position < searchResults.size()) {
                    RadioStation st = searchResults.get(position);
                    showSearchOverlay(false);
                    netRadio.play(st);
                }
            }
        });
        panel.addView(searchListView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        parent.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showSearchOverlay(boolean show) {
        if (searchOverlay != null) {
            searchOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && searchResults.isEmpty()) {
                performSearch("top");
            }
        }
    }

    private void performSearch(String query) {
        if (query == null) query = "";
        query = query.trim();

        searchOverlayTitle.setText(s("Pretraživanje…", "Searching…", "Buscando…"));

        RadioBrowserApi.Callback cb = new RadioBrowserApi.Callback() {
            @Override
            public void onSuccess(List<RadioStation> stations) {
                searchResults.clear();
                if (stations != null) {
                    searchResults.addAll(stations);
                }
                searchOverlayTitle.setText(s("Rezultati (" + searchResults.size() + ")",
                        "Results (" + searchResults.size() + ")",
                        "Resultados (" + searchResults.size() + ")"));
                searchAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(String message) {
                searchOverlayTitle.setText(s("Greška u pretrazi", "Search error", "Error de búsqueda"));
                Ui.toast(act, message);
            }
        };

        if (query.isEmpty() || "top".equalsIgnoreCase(query)) {
            RadioBrowserApi.getTopStations(30, cb);
        } else {
            RadioBrowserApi.searchByName(query, cb);
        }
    }

    private void showAddUrlDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(act);
        b.setTitle(s("Dodaj Internet radio stream", "Add Custom Radio Stream", "Añadir emisora personalizad"));

        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(Mib.px(act, 20), Mib.px(act, 10), Mib.px(act, 20), Mib.px(act, 10));

        final EditText nameInput = new EditText(act);
        nameInput.setHint(s("Ime stanice", "Station Name", "Nombre de la emisora"));
        nameInput.setSingleLine(true);
        layout.addView(nameInput);

        final EditText urlInput = new EditText(act);
        urlInput.setHint("http://stream.example.com/live");
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        layout.addView(urlInput);

        b.setView(layout);

        b.setPositiveButton(s("Pusti", "Play", "Reproducir"), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String name = nameInput.getText().toString().trim();
                String url = urlInput.getText().toString().trim();

                if (!RadioStation.isValidUrl(url)) {
                    Ui.toast(act, s("Neispravan URL stream-a", "Invalid Stream URL", "URL de transmisión no válida"));
                    return;
                }

                if (name.isEmpty()) name = "Online Station";
                RadioStation st = new RadioStation(name, url, "", "", "", "", "");
                netRadio.play(st);
            }
        });

        b.setNegativeButton(s("Otkaz", "Cancel", "Cancelar"), null);
        b.show();
    }

    private void setInternetMode(boolean on) {
        if (on == internetMode) return;
        if (on) {
            if (fmRadio.isActive()) fmRadio.stop();
        } else {
            if (!fmRadio.available()) {
                Ui.toast(act, s("FM tjuner nije dostupan", "FM tuner is not available", "Sintonizador FM no disponible"));
                return;
            }
            if (netRadio.isActive()) netRadio.stop();
            fmRadio.start();
        }
        internetMode = on;
        Prefs.put(act, P_RADIO_MODE, on ? "internet" : "fm");
        updateBarKeys();
        updateDisplay();
    }

    private void updateBarKeys() {
        boolean fmAvail = fmRadio.available();
        modeKey.setLabel(internetMode ? s("Internet Radio", "Internet Radio") : s("FM Radio", "FM Radio"));

        searchKey.setVisibility(internetMode ? View.VISIBLE : View.GONE);
        addUrlKey.setVisibility(internetMode ? View.VISIBLE : View.GONE);

        fmListKey.setVisibility(!internetMode && fmAvail ? View.VISIBLE : View.GONE);
        fmManualKey.setVisibility(!internetMode && fmAvail ? View.VISIBLE : View.GONE);
    }


    private View arrowBox(int icon, View.OnClickListener l) {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(Mib.box(act));
        ImageView iv = Mib.icon(act, icon, Mib.WHITE);
        b.addView(iv, new FrameLayout.LayoutParams(Mib.px(act, 54), Mib.px(act, 36), Gravity.CENTER));
        b.setOnClickListener(l);
        return b;
    }

    private FrameLayout.LayoutParams boxParams(int size, int side, int top, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, side, Gravity.TOP | gravity);
        p.width = size;
        p.height = size;
        p.topMargin = top;
        p.leftMargin = p.rightMargin = side;
        return p;
    }

    private void updateDots(int page) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == page ? Mib.BLUE : 0xFFE6E6EA);
            View d = dots.getChildAt(i);
            d.setBackground(g);
            d.setScaleX(i == page ? 1.2f : 0.85f);
            d.setScaleY(i == page ? 1.2f : 0.85f);
        }
    }

    private void setManualFm(boolean on) {
        manualFm = on;
        fmManualKey.setActive(on);
        presetsArea.setVisibility(on ? View.GONE : View.VISIBLE);
        manualFmArea.setVisibility(on ? View.VISIBLE : View.GONE);
        updateDisplay();
    }

    private void showFmList(boolean on) {
        fmListArea.setVisibility(on ? View.VISIBLE : View.GONE);
        fmListKey.setActive(on);
        if (on) updateDisplay();
    }

    private void stepInternetPreset(int dir) {
        int cur = netRadio.presetIndexOfCurrent();
        int next = cur + dir;
        if (next < 0) next = RadioController.PRESETS - 1;
        if (next >= RadioController.PRESETS) next = 0;

        for (int i = 0; i < RadioController.PRESETS; i++) {
            int idx = (next + i * dir + RadioController.PRESETS) % RadioController.PRESETS;
            RadioStation st = netRadio.preset(idx);
            if (st != null) {
                netRadio.play(st);
                break;
            }
        }
    }

    @Override
    public void onShow() {
        if (!internetMode && fmRadio.available()) {
            fmRadio.start();
        }

        fmRadio.addListener(this);
        netRadio.addListener(this);
        RadioLogos.get(act).addListener(this);
        updateDisplay();
    }

    @Override
    public void onHide() {
        fmRadio.removeListener(this);
        netRadio.removeListener(this);
        RadioLogos.get(act).removeListener(this);
    }

    @Override
    public void onLogo() {
        updateDisplay();
    }

    @Override
    public boolean onBack() {
        if (searchOverlay != null && searchOverlay.getVisibility() == View.VISIBLE) {
            showSearchOverlay(false);
            return true;
        }
        if (fmListArea.getVisibility() == View.VISIBLE) {
            showFmList(false);
            return true;
        }
        if (manualFm) {
            setManualFm(false);
            return true;
        }
        return false;
    }

    @Override
    public void onRadioChanged() {
        if (!internetMode) updateDisplay();
    }

    @Override
    public void onInternetRadioChanged() {
        if (internetMode) updateDisplay();
    }

    private void updateDisplay() {
        if (freqText == null) return;

        if (internetMode) {
            updateInternetDisplay();
        } else {
            updateFmDisplay();
        }
    }

    private void updateFmDisplay() {
        fmScale.setFreq(fmRadio.freq());
        internetControlRow.setVisibility(View.GONE);
        manualFmArea.setVisibility(manualFm ? View.VISIBLE : View.GONE);
        presetsArea.setVisibility(manualFm ? View.GONE : View.VISIBLE);

        String ps = fmRadio.ps().trim(), rt = fmRadio.rt();
        boolean named = fmRadio.isActive() && ps.length() > 0 && !manualFm;

        freqText.setText(named ? ps : RadioController.format(fmRadio.freq()));
        unitText.setVisibility(named ? View.GONE : View.VISIBLE);

        StringBuilder sb = new StringBuilder(named ? RadioController.format(fmRadio.freq()) + " MHz" : "");
        if (rt.length() > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append(rt);
        if (fmRadio.stereo()) sb.append(sb.length() > 0 ? "   " : "").append("ST");
        if (!fmRadio.isActive()) sb.setLength(0);

        infoText.setText(sb.toString());

        int cur = fmRadio.presetIndexOfCurrent();
        for (int i = 0; i < presets.length; i++) {
            RadioController.Station st = fmRadio.preset(i);
            presets[i].bindFm(st, i == cur);
        }

        fmAdapter.notifyDataSetChanged();
    }

    private void updateInternetDisplay() {
        manualFmArea.setVisibility(View.GONE);
        presetsArea.setVisibility(View.VISIBLE);
        internetControlRow.setVisibility(View.VISIBLE);

        RadioStation st = netRadio.currentStation();

        if (st == null) {
            freqText.setText(s("Internet Radio", "Internet Radio"));
            unitText.setVisibility(View.GONE);
            infoText.setText(s("Izaberite ili pretražite stanicu", "Select or search for a station", "Elige o busca una emisora"));
            internetPlayBtn.setImageResource(R.drawable.ui_ic_radio);
        } else {
            freqText.setText(st.getDisplayName());
            unitText.setVisibility(View.GONE);

            StringBuilder sb = new StringBuilder();
            if (netRadio.isBuffering()) {
                sb.append(s("Učitavanje…", "Connecting…", "Conectando…"));
            } else if (netRadio.isError()) {
                sb.append(s("Greška: ", "Error: ", "Error: ")).append(netRadio.errorMessage());
            } else {
                if (!netRadio.trackTitle().isEmpty()) {
                    sb.append(netRadio.trackTitle());
                    if (!netRadio.artist().isEmpty()) {
                        sb.append("  ·  ").append(netRadio.artist());
                    }
                } else if (!st.country.isEmpty() || !st.tags.isEmpty()) {
                    sb.append(st.country).append(" ").append(st.tags);
                } else {
                    sb.append("HTTP Stream");
                }
            }

            infoText.setText(sb.toString());
            internetPlayBtn.setImageResource(netRadio.isPlaying() ? R.drawable.ui_ic_note : R.drawable.ui_ic_radio);
        }

        int cur = netRadio.presetIndexOfCurrent();
        for (int i = 0; i < presets.length; i++) {
            RadioStation presetStation = netRadio.preset(i);
            presets[i].bindInternet(presetStation, i == cur);
        }
    }

    // ---- Preset tile -------------------------------------------------------------------------

    private class PresetTile extends LinearLayout {
        final int index;
        final FrameLayout face;
        final TextView number, name;
        final ImageView logo;
        final View line;

        PresetTile(int idx) {
            super(act);
            index = idx;
            setOrientation(VERTICAL);

            face = new FrameLayout(act);
            face.setBackground(Mib.tile(act));

            logo = new ImageView(act);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams lgl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            lgl.leftMargin = lgl.rightMargin = Mib.px(act, 18);
            lgl.topMargin = Mib.px(act, 30);
            lgl.bottomMargin = Mib.px(act, 12);
            face.addView(logo, lgl);

            number = Mib.text(act, String.valueOf(idx % PER_PAGE + 1), 34, Mib.semibold(act), Mib.WHITE);
            FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
            nl.rightMargin = Mib.px(act, 10);
            nl.topMargin = Mib.px(act, 2);
            face.addView(number, nl);

            addView(face, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Mib.px(act, 110)));

            line = new View(act);
            line.setBackgroundColor(Mib.BLUE);
            addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(2, Mib.px(act, 3))));

            name = Mib.text(act, "", 24, Mib.semibold(act), Mib.WHITE);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, Mib.px(act, 6), 0, 0);
            addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            face.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (internetMode) {
                        if (netRadio.preset(index) == null) saveInternet();
                        else netRadio.recallPreset(index);
                    } else {
                        if (fmRadio.preset(index) == null) saveFm();
                        else fmRadio.recallPreset(index);
                    }
                }
            });

            face.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (internetMode) {
                        saveInternet();
                    } else {
                        saveFm();
                    }
                    return true;
                }
            });
        }

        void saveFm() {
            fmRadio.savePreset(index);
            Ui.toast(act, s("Sačuvano na " + (index % PER_PAGE + 1), "Stored on " + (index % PER_PAGE + 1),
                    "Guardada en " + (index % PER_PAGE + 1)));
        }

        void saveInternet() {
            if (netRadio.currentStation() == null) {
                Ui.toast(act, s("Nijedna stanica ne svira", "No station playing", "No hay emisora reproduciéndose"));
                return;
            }
            netRadio.savePreset(index);
            Ui.toast(act, s("Sačuvano na " + (index % PER_PAGE + 1), "Stored on " + (index % PER_PAGE + 1),
                    "Guardada en " + (index % PER_PAGE + 1)));
        }

        void bindFm(RadioController.Station st, boolean current) {
            if (st == null) name.setText(s("Prazno", "Empty"));
            else name.setText(st.name.length() > 0 ? st.name : RadioController.format(st.freq));

            Bitmap b = st != null && st.name.length() > 0
                    ? RadioLogos.get(act).logo(st.name) : null;
            logo.setImageBitmap(b);

            int c = current ? Mib.BLUE : Mib.WHITE;
            number.setTextColor(c);
            name.setTextColor(c);

            ViewGroup.LayoutParams lp = line.getLayoutParams();
            int h = Math.max(2, Mib.px(act, current ? 5 : 3));
            if (lp.height != h) {
                lp.height = h;
                line.setLayoutParams(lp);
            }
        }

        void bindInternet(RadioStation st, boolean current) {
            if (st == null) name.setText(s("Prazno", "Empty"));
            else name.setText(st.getDisplayName());

            Bitmap b = st != null && !st.name.isEmpty()
                    ? RadioLogos.get(act).logo(st.name, st.logoUrl) : null;
            logo.setImageBitmap(b);

            int c = current ? Mib.BLUE : Mib.WHITE;
            number.setTextColor(c);
            name.setTextColor(c);

            ViewGroup.LayoutParams lp = line.getLayoutParams();
            int h = Math.max(2, Mib.px(act, current ? 5 : 3));
            if (lp.height != h) {
                lp.height = h;
                line.setLayoutParams(lp);
            }
        }
    }

    // ---- Adapters ----------------------------------------------------------------------------

    private class FmStationAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return fmRadio == null ? 0 : fmRadio.stations().size() + 1;
        }

        @Override
        public Object getItem(int i) {
            return i;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Mib.px(act, 10), 0, Mib.px(act, 10), 0);
            row.setMinimumHeight(Mib.px(act, 72));

            ImageView iv;
            TextView t, f;

            if (pos == 0) {
                iv = Mib.icon(act, R.drawable.ui_ic_refresh, Mib.WHITE);
                t = Mib.text(act, fmRadio.scanning() ? s("Zaustavi pretragu", "Stop scan")
                        : s("Ažuriraj listu stanica", "Update station list"), 28, Mib.regular(act), Mib.WHITE);
                f = Mib.text(act, "", 26, Mib.regular(act), Mib.GREY);
            } else {
                RadioController.Station st = fmRadio.stations().get(pos - 1);
                boolean cur = st.freq == fmRadio.freq();
                Bitmap lb = st.name.length() > 0 ? RadioLogos.get(act).logo(st.name) : null;

                if (lb != null) {
                    iv = new ImageView(act);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setImageBitmap(lb);
                } else {
                    iv = Mib.icon(act, R.drawable.ui_ic_radio, cur ? Mib.BLUE : Mib.WHITE);
                }
                t = Mib.text(act, st.name.length() > 0 ? st.name : "FM", 28, Mib.regular(act), cur ? Mib.BLUE : Mib.WHITE);
                f = Mib.text(act, RadioController.format(st.freq) + " MHz", 26, Mib.regular(act), cur ? Mib.BLUE : Mib.GREY);
            }

            row.addView(iv, new LinearLayout.LayoutParams(Mib.px(act, 44), Mib.px(act, 44)));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.leftMargin = Mib.px(act, 18);
            row.addView(t, tl);
            row.addView(f);
            return row;
        }
    }

    private class SearchResultsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return searchResults.size();
        }

        @Override
        public Object getItem(int i) {
            return searchResults.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Mib.px(act, 12), Mib.px(act, 10), Mib.px(act, 12), Mib.px(act, 10));

            RadioStation st = searchResults.get(pos);

            ImageView iv = new ImageView(act);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Bitmap b = !st.name.isEmpty() ? RadioLogos.get(act).logo(st.name, st.logoUrl) : null;
            if (b != null) {
                iv.setImageBitmap(b);
            } else {
                iv.setImageResource(R.drawable.ui_ic_radio);
                iv.setImageTintList(android.content.res.ColorStateList.valueOf(Mib.WHITE));
            }
            row.addView(iv, new LinearLayout.LayoutParams(Mib.px(act, 44), Mib.px(act, 44)));

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);

            TextView nameView = Mib.text(act, st.getDisplayName(), 26, Mib.semibold(act), Mib.WHITE);
            col.addView(nameView);

            StringBuilder sub = new StringBuilder();
            if (!st.country.isEmpty()) sub.append("[").append(st.country).append("] ");
            if (!st.tags.isEmpty()) sub.append(st.tags);
            if (sub.length() == 0) sub.append(st.streamUrl);

            TextView tagView = Mib.text(act, sub.toString(), 20, Mib.regular(act), Mib.GREY);
            col.addView(tagView);

            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cl.leftMargin = Mib.px(act, 16);
            row.addView(col, cl);

            return row;
        }
    }
}
