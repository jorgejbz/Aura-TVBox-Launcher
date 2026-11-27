package com.jajua.aura;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * "Recomendaciones": how to use the box's streaming apps, readable from the sofa. Shown by itself once
 * per boot until the user ticks "No volver a mostrar" and presses "Continuar"; always reachable from
 * the "Recomendaciones" button next to the network status on the home screen.
 *
 * Up and Down scroll the text; Left and Right move between the tick box and "Continuar".
 */
public final class RecommendationsActivity extends Activity {
    /** Shown when the user presses "Continuar" with the box ticked; survives reboots (app data). */
    private static final String PREFS = "recommendations";
    private static final String HIDE = "hide_on_start";
    private static final String SHOWN_BOOT = "shown_boot";
    /** Android before 7.0 has no boot counter: once per Aura process there. */
    private static boolean shownThisProcess;

    static final String XUPER_VERSION = XuperApps.XUPER_VERSION;
    /** The version Crystal Clear shows inside the app (Android reads 4.34.4 from its manifest). */
    static final String CRYSTAL_VERSION = XuperApps.CRYSTAL_REAL_VERSION;
    /** Opened with the home screen button: only "Continuar" (the tick box is for the start-up notice). */
    static final String EXTRA_MANUAL = "manual";
    /** The "Recomendaciones" page: how to look after the box (always opened by hand). */
    static final String EXTRA_CARE = "care";
    /** The "Horarios" page: when each app works (always opened by hand). */
    static final String EXTRA_SCHEDULE = "schedule";

    private static final int AMBER = 0xFFFFB74D;
    private static final int GREEN = 0xFF7BD88F;

    private ScrollView scroll;
    private View tick;
    private TextView tickBox;
    private boolean dontShow;
    private boolean manual;
    private boolean care;
    private boolean schedule;
    private View speedCard;
    private TextView speedResult;
    private TextView speedButton;
    private Thread speedThread;

    // ------------------------------------------------------------ when to show

    /**
     * True once per boot (the first time Aura's home opens) unless the user turned it off. Aura's process
     * can be killed and restarted many times a day on a 1 GB box; that must not bring the screen back.
     */
    static boolean dueAtStart(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(HIDE, false)) return false;
        int boot = bootCount(c);
        if (boot < 0) {
            if (shownThisProcess) return false;
            shownThisProcess = true;
            return true;
        }
        if (p.getInt(SHOWN_BOOT, -1) == boot) return false;
        p.edit().putInt(SHOWN_BOOT, boot).apply();
        return true;
    }

    private static int bootCount(Context c) {
        if (Build.VERSION.SDK_INT < 24) return -1;
        try {
            return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException e) {
            return -1;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ screen

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        care = getIntent().getBooleanExtra(EXTRA_CARE, false);
        schedule = getIntent().getBooleanExtra(EXTRA_SCHEDULE, false);
        manual = care || schedule || getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        setContentView(build());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int step = Math.round(scroll.getHeight() * 0.4f), page = Math.round(scroll.getHeight() * 0.85f);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: scroll.smoothScrollBy(0, -step); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: scroll.smoothScrollBy(0, step); return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP: scroll.smoothScrollBy(0, -page); return true;
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN: scroll.smoothScrollBy(0, page); return true;
                default: break;
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN: return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** Back closes without saving the tick, like leaving the box unticked. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finish();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void onContinue() {
        if (dontShow) prefs(this).edit().putBoolean(HIDE, true).apply();
        finish();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        AuraView aura = new AuraView(this);
        root.addView(aura, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Ui.u(Ui.SAFE_X), Ui.u(Ui.SAFE_Y), Ui.u(Ui.SAFE_X), Ui.u(Ui.SAFE_Y));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        TextView kicker = Ui.text(this, 12, Ui.medium, 0.5f);
        kicker.setText(R.string.recs_kicker);
        kicker.setLetterSpacing(0.12f);
        column.addView(kicker);
        TextView title = Ui.text(this, 32, Ui.medium, 1f);
        title.setText(schedule ? R.string.sched_title : care ? R.string.care_title : R.string.recs_title);
        column.addView(title, margins(0, 6, 0, 0));
        TextView sub = Ui.text(this, 14, Ui.regular, 0.6f);
        sub.setText(schedule ? R.string.sched_subtitle : care ? R.string.care_subtitle : R.string.recs_subtitle);
        column.addView(sub, margins(0, 6, 0, 0));

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Ui.u(36));
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = Ui.u(16);
        column.addView(scroll, slp);

        LinearLayout cols = new LinearLayout(this);
        cols.setPadding(0, Ui.u(4), 0, Ui.u(12));
        scroll.addView(cols, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout left = vertical(), right = vertical();
        cols.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -2, 1f);
        rp.leftMargin = Ui.u(18);
        cols.addView(right, rp);

        if (schedule) {
            left.addView(scheduleXuper());
            speedCard = speedCard();
            left.addView(speedCard, margins(0, 16, 0, 0));
            right.addView(scheduleAmigOnly());
            right.addView(scheduleWhy(), margins(0, 16, 0, 0));
            right.addView(supportCard("✉"), margins(0, 16, 0, 0));
        } else if (care) {
            left.addView(careInternet());
            left.addView(careHeat(), margins(0, 16, 0, 0));
            left.addView(careRemote(), margins(0, 16, 0, 0));
            right.addView(carePower());
            right.addView(careCleaning(), margins(0, 16, 0, 0));
            right.addView(careApps(), margins(0, 16, 0, 0));
        } else {
            left.addView(magis());
            left.addView(crystal(), margins(0, 16, 0, 0));
            left.addView(moreApps(), margins(0, 16, 0, 0));
            left.addView(supportCard("F"), margins(0, 16, 0, 0));
            right.addView(amigonly());
            right.addView(stremio(), margins(0, 16, 0, 0));
        }

        column.addView(bottomBar(), margins(0, 14, 0, 0));
        return root;
    }

    // ------------------------------------------------------------ content

    private View magis() {
        LinearLayout s = section("A", 0xFF5B8CFF, "Magis TV / Xuper", "com.android.mgstv", XUPER_VERSION,
                "Tercera opción de Favoritas");
        steps(s,
                "Al usarla por primera vez tienes 7 días gratis.",
                "Al terminar ese periodo puedes:\n•  Borrar los datos de la app para volver a tener el periodo gratis (si el servicio lo permite).\n•  Iniciar sesión con tu cuenta de Xuper.",
                "¿Aún no tienes cuenta? Regístrate desde la misma opción de vinculación: escribe tu correo, espera el código de verificación que te llegará, escríbelo y listo.");
        warning(s, "No actualices esta app",
                "Al abrirla puede aparecer «Actualización de versión»: elige siempre «Cancelar», nunca «Actualizar». No la actualices a otra versión sin revisar antes esta guía de uso. Si ya la actualizaste y dejó de funcionar, usa «Reinstalar Xuper’s» en Sistema para volver a la versión recomendada.");
        return s;
    }

    private View crystal() {
        LinearLayout s = section("B", 0xFF4FD1C5, "Xuper Crystal Clear", "com.xuper.crystalclear",
                CRYSTAL_VERSION, null);
        paragraph(s, "No necesita iniciar sesión. Comprueba que tengas la versión " + CRYSTAL_VERSION
                + " (es la que muestra la app al abrirla; en los Ajustes de Android aparece como 4.34.4) y disfrútala.");
        warning(s, "¿Te pide iniciar sesión?",
                "Entonces no tienes la versión " + CRYSTAL_VERSION + " que necesitas. Reinstala Xuper Crystal Clear desde su opción en Aura:");
        steps(s,
                "Regresa a la pantalla principal de Aura.",
                "Entra en «Sistema».",
                "Elige «Reinstalar Xuper’s», junto a «Buscar actualizaciones».",
                "Selecciona «Xuper Crystal Clear» y confirma.");
        paragraph(s, "Aura descarga e instala la versión oficial por ti.");
        return s;
    }

    private View amigonly() {
        LinearLayout s = section("C", 0xFF2AABEE, "XuperAmigOnlyTV", "com.xuper.amigonly.tv.new", null,
                "Inicia sesión con Telegram");
        steps(s,
                "Abre XuperAmigOnlyTV: aparecerá un código QR.",
                "Escanéalo con la cámara de tu celular; se abrirá un chatbot de Telegram.",
                "En el chat escribe /start.",
                "Elige la opción para unirte al canal y completa la suscripción.",
                "Regresa a la TV Box y selecciona «Activar», abajo a la derecha.");
        note(s, "Cada cuenta de Telegram puede tener 2 dispositivos vinculados (2 TV Box).");
        return s;
    }

    private View stremio() {
        LinearLayout s = section("D", 0xFF7B5BF5, "Stremio", "com.stremio.one", null,
                "Inicia sesión con un código QR");
        steps(s,
                "Abre Stremio: aparecerá la pantalla «Login» con un código QR.",
                "Escanéalo con la cámara de tu celular (o entra desde el celular al enlace link.stremio.com que aparece junto al código).",
                "En el celular, si ya tienes cuenta, inicia sesión con tu correo y contraseña.",
                "Si no tienes cuenta, elige registrarte: escribe tu correo, crea una contraseña y acepta.",
                "Listo: la TV Box entra sola a tu cuenta en unos segundos.");
        paragraph(s, "El código dura 5 minutos. Si se vence, selecciona «Request a new link» en la TV para obtener uno nuevo.");
        note(s, "¿Necesitas los addons (estos sirven para las películas y series gratuitas)? Comunícate por Telegram con @jorge_jbz.");
        qrRow(s, BoxCare.MSG_ADDONS, "Escanéalo con tu celular: se abrirá Telegram con el mensaje para pedir los addons.");
        return s;
    }

    private View moreApps() {
        LinearLayout s = section("E", 0xFFB388FF, "Instalar otras apps", null, null, null);
        subheading(s, "Opción 1 · Google Play Store");
        paragraph(s, "Abre Play Store, inicia sesión con tu cuenta de Google y busca e instala las apps que quieras.");
        subheading(s, "Opción 2 · Memoria USB");
        steps(s,
                "Descarga los archivos APK que necesites y cópialos a una memoria USB.",
                "Conecta la memoria a la TV Box.",
                "Abre «Archivos», busca el APK y selecciónalo para instalarlo.");
        return s;
    }

    // ------------------------------------------------------------ when each app works ("Horarios")

    /** For whoever chose Crystal Clear or Magis TV: when they work and what to use after 10 p.m. */
    private View scheduleXuper() {
        LinearLayout s = section("1", 0xFF5B8CFF, "Si decidiste usar Xuper Crystal Clear o Magis TV / Xuper", null,
                null, "Xuper Crystal Clear " + CRYSTAL_VERSION + "  ·  Magis TV / Xuper " + XUPER_VERSION);
        steps(s,
                "Funcionan con normalidad de 8:00 a.m. a 10:00 p.m.",
                "A las 10:00 p.m. se caen sus servicios y se restablecen a las 8:00 a.m.",
                "Después de las 10:00 p.m. te recomendamos usar Stremio o XuperAmigOnlyTV para seguir disfrutando de tus programas y series favoritas.");
        warning(s, "Importante: no actualices ni desinstales",
                "Si Xuper Crystal Clear o Magis TV / Xuper dejan de cargar, o te muestran un aviso para actualizarse, haz caso omiso: no las actualices ni las desinstales.\n\n"
                        + "Se trata de una falla temporal del servicio, frecuente en estos días, y normalmente se resuelve sola por la mañana. Mientras tanto, puedes usar Stremio o XuperAmigOnlyTV.");
        return s;
    }

    /** For whoever always uses XuperAmigOnlyTV: it works at night, with an occasional early-morning outage. */
    private View scheduleAmigOnly() {
        LinearLayout s = section("2", 0xFF2AABEE, "Si decidiste usar XuperAmigOnlyTV", null, null, null);
        paragraph(s, "Puedes usarla a cualquier hora, también en la noche.");
        warning(s, "En la madrugada",
                "Hay días en que se puede caer o dejar de cargar a las 3:00 o 4:00 a.m. Se restablece a las 8:00 a.m., al igual que Xuper Crystal Clear y Magis TV / Xuper.");
        return s;
    }

    private View scheduleWhy() {
        LinearLayout s = section("?", 0xFF4FD1C5, "¿Por qué pasa?", null, null, null);
        paragraph(s, "Últimamente estos servicios están presentando errores, por eso se caen en ese horario. No es una falla de tu TV Box ni de tu internet: no necesitas reiniciarla ni reinstalar las apps.");
        return s;
    }

    // ------------------------------------------------------------ support and internet test

    /** "Soporte": a QR that opens Telegram with the support message already written. */
    private View supportCard(String letter) {
        LinearLayout s = section(letter, 0xFF2AABEE, "Soporte", null, null, null);
        paragraph(s, "¿Tienes dudas o algún problema con tu TV Box? Escríbeme por Telegram a @jorge_jbz.");
        qrRow(s, BoxCare.MSG_SUPPORT, "Escanéalo con la cámara de tu celular: se abrirá Telegram con un mensaje listo para enviar.");
        return s;
    }

    /** QR (white square, readable from a phone) with a short explanation beside it. */
    private void qrRow(LinearLayout s, String message, String text) {
        QrCode qr = QrCode.encode(BoxCare.telegramLink(message));
        if (qr == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView img = new android.widget.ImageView(this);
        img.setImageBitmap(qr.toBitmap(4, 3));
        img.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        // Sharp modules when scaled up on a TV.
        img.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        if (img.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            ((android.graphics.drawable.BitmapDrawable) img.getDrawable()).setFilterBitmap(false);
        }
        int side = Ui.u(130);
        row.addView(img, new LinearLayout.LayoutParams(side, side));
        TextView t = body(text, 0.86f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.u(16);
        row.addView(t, tlp);
        s.addView(row, margins(0, 14, 0, 0));
    }

    /** "¿Es tu internet?": the result of "Probar mi internet" appears here. */
    private View speedCard() {
        LinearLayout s = section("⚡", 0xFF7BD88F, "¿Es tu internet?", null, null, null);
        paragraph(s, "Selecciona «Probar mi internet» abajo para medir tu velocidad (tarda unos 10 segundos).");
        speedResult = body("", 1f);
        speedResult.setVisibility(View.GONE);
        s.addView(speedResult, margins(0, 12, 0, 0));
        return s;
    }

    private void runSpeedTest() {
        if (speedThread != null && speedThread.isAlive()) return;
        speedResult.setVisibility(View.VISIBLE);
        speedResult.setTextColor(Ui.TEXT);
        speedResult.setText("Midiendo tu internet…");
        speedButton.setEnabled(false);
        speedButton.setAlpha(0.5f);
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, speedCard.getTop() - Ui.u(10))));
        speedThread = new Thread(() -> {
            double mbps = BoxCare.measureSpeed(pct -> runOnUiThread(() -> {
                if (!isDestroyed()) speedResult.setText("Midiendo tu internet…  " + pct + "%");
            }));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                speedButton.setEnabled(true);
                speedButton.setAlpha(1f);
                showSpeed(mbps);
            });
        }, "aura-velocidad");
        speedThread.start();
    }

    private void showSpeed(double mbps) {
        String text;
        int color;
        if (mbps < 0) {
            text = "No hay conexión a internet. Revisa el cable Ethernet o el Wi-Fi, y reinicia tu módem.";
            color = 0xFFFF8A65;
        } else {
            String v = String.format(Locale.getDefault(), "%.1f Mbps", mbps);
            if (mbps >= 15) {
                text = "Tu internet: " + v + ". Excelente, alcanza para Full HD. Si una app no carga, es el servicio de esa app, no tu internet.";
                color = 0xFF7BD88F;
            } else if (mbps >= 5) {
                text = "Tu internet: " + v + ". Suficiente para HD. Si una app no carga, es el servicio de esa app, no tu internet.";
                color = 0xFF7BD88F;
            } else if (mbps >= 2) {
                text = "Tu internet: " + v + ". Algo lento: los videos pueden detenerse. Usa cable Ethernet o acerca la TV Box al módem.";
                color = 0xFFFFB74D;
            } else {
                text = "Tu internet: " + v + ". Muy lento para ver videos. Reinicia tu módem, usa cable Ethernet o consulta con tu compañía de internet.";
                color = 0xFFFF8A65;
            }
        }
        speedResult.setText(text);
        speedResult.setTextColor(color);
    }

    @Override
    protected void onDestroy() {
        if (speedThread != null) speedThread.interrupt();
        super.onDestroy();
    }

    // ------------------------------------------------------------ care of the box ("Recomendaciones")

    private View careInternet() {
        LinearLayout s = section("1", 0xFF5B8CFF, "Internet", null, null, null);
        paragraph(s, "Para una mejor señal de internet te recomendamos usarla por cable Ethernet.");
        bullets(s,
                "Si usas Wi-Fi, colócala lo más cerca posible del módem, sin paredes ni muebles de por medio.",
                "Si los videos se detienen seguido, reinicia el módem y la TV Box.");
        return s;
    }

    private View careHeat() {
        LinearLayout s = section("2", 0xFFFF8A65, "Temperatura", null, null, null);
        paragraph(s, "Mantenla en un lugar que no esté cerrado para evitar el sobrecalentamiento: nada de cajones ni muebles cerrados.");
        warning(s, "Si se calienta",
                "En dado caso de que se caliente la TV Box verás pequeños reinicios. Desconéctala, espera a que se enfríe y vuelve a conectarla.");
        bullets(s,
                "Deja espacio libre a su alrededor y no la tapes.",
                "No la pongas encima de aparatos que calientan (módem, decodificador, consola) ni pegada a las rejillas de la TV.",
                "Evita el sol directo.");
        return s;
    }

    private View careRemote() {
        LinearLayout s = section("3", 0xFF4FD1C5, "Control remoto", null, null, null);
        bullets(s,
                "Apunta el control hacia el frente de la TV Box, sin objetos en medio.",
                "Si deja de responder o tarda, cambia las pilas.",
                "Quita las pilas si no lo usarás en mucho tiempo.");
        return s;
    }

    private View carePower() {
        LinearLayout s = section("4", 0xFFFFB74D, "Corriente eléctrica", null, null, null);
        bullets(s,
                "Usa siempre su cargador original.",
                "Conéctala a un regulador o supresor de picos: los apagones y las variaciones de voltaje pueden dañarla.",
                "Desconéctala durante tormentas eléctricas.",
                "No la desconectes mientras se instala o actualiza una app.",
                "Si se traba, desconéctala, espera 15 segundos y vuelve a conectarla.");
        return s;
    }

    private View careCleaning() {
        LinearLayout s = section("5", 0xFFB388FF, "Limpieza y cuidado", null, null, null);
        bullets(s,
                "Límpiala con un paño seco; nunca uses líquidos.",
                "Protégela del polvo y la humedad: lejos de ventanas, plantas y la cocina.",
                "No la golpees ni la dejes caer, y no jales los cables.");
        return s;
    }

    private View careApps() {
        LinearLayout s = section("6", 0xFF7BD88F, "Apps y espacio", null, null, null);
        bullets(s,
                "Deja al menos 1 GB libre (lo ves arriba en la pantalla principal); con poco espacio todo se vuelve lento.",
                "Desinstala las apps que no uses.",
                "Instala apps solo de Play Store o de fuentes de confianza.",
                "No actualices Magis TV / Xuper ni Xuper Crystal Clear sin revisar la Guía de uso.");
        return s;
    }

    // ------------------------------------------------------------ building blocks

    /** Short tips, one per line, with a small dot. */
    private void bullets(LinearLayout s, String... items) {
        for (int i = 0; i < items.length; i++) {
            LinearLayout row = new LinearLayout(this);
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0x99F5F5F7);
            dot.setBackground(d);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.u(6), Ui.u(6));
            dlp.topMargin = Ui.u(9);
            dlp.leftMargin = Ui.u(4);
            row.addView(dot, dlp);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
            blp.leftMargin = Ui.u(12);
            row.addView(body(items[i], 0.86f), blp);
            s.addView(row, margins(0, i == 0 ? 12 : 8, 0, 0));
        }
    }

    /** A rounded panel with a letter, the app name and, for apps, the recommended and installed versions. */
    private LinearLayout section(String letter, int color, String name, String pkg, String recommended,
            String extra) {
        LinearLayout s = vertical();
        int pad = Ui.u(18);
        s.setPadding(pad, Ui.u(16), pad, Ui.u(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setStroke(Math.max(1, Ui.u(1)), 0x1FFFFFFF);
        bg.setCornerRadius(Ui.uf(16));
        s.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = Ui.text(this, 14, Ui.medium, 1f);
        dot.setText(letter);
        dot.setGravity(Gravity.CENTER);
        dot.setTextColor(0xFF07080B);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        dot.setBackground(circle);
        head.addView(dot, new LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)));
        TextView t = Ui.text(this, 20, Ui.medium, 1f);
        t.setText(name);
        t.setSingleLine(false); // Long titles ("Si decidiste usar …") wrap instead of being cut.
        t.setEllipsize(null);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.u(12);
        head.addView(t, tlp);
        s.addView(head);

        LinearLayout chips = new LinearLayout(this);
        boolean any = false;
        if (recommended != null) {
            chips.addView(chip(getString(R.string.recs_recommended, recommended), 0x26FFFFFF, Ui.TEXT));
            any = true;
        }
        if (pkg != null) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
            if (any) clp.leftMargin = Ui.u(8);
            final TextView have = chip(getString(R.string.recs_installed, "…"), 0x14FFFFFF, 0xB3F5F5F7);
            chips.addView(have, clp);
            any = true;
            // The real version may mean reading the app's APK (Crystal Clear): fill the chip when known.
            final XuperApps.Target target = XuperApps.target(pkg);
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String v = target != null ? XuperApps.installedVersion(app, target) : installed(pkg);
                runOnUiThread(() -> {
                    if (!isDestroyed()) paintInstalled(have, v, recommended);
                });
            }, "aura-version").start();
        }
        if (any) s.addView(chips, margins(0, 12, 0, 0));
        if (extra != null) {
            TextView x = Ui.text(this, 13, Ui.regular, 0.6f);
            x.setText(extra);
            s.addView(x, margins(0, any ? 8 : 10, 0, 0));
        }
        return s;
    }

    private void paintInstalled(TextView chip, String have, String recommended) {
        String text = have == null ? getString(R.string.recs_not_installed) : getString(R.string.recs_installed, have);
        int fg = have == null ? 0xB3F5F5F7 : recommended == null || have.equals(recommended) ? GREEN : AMBER;
        if (have != null && recommended != null && have.equals(recommended)) text += "  ✓";
        chip.setText(text);
        chip.setTextColor(fg);
        ((GradientDrawable) chip.getBackground()).setColor(Ui.withAlpha(fg, 0.16f));
    }

    private TextView chip(String text, int bg, int fg) {
        TextView c = Ui.text(this, 12.5f, Ui.medium, 1f);
        c.setText(text);
        c.setTextColor(fg);
        c.setPadding(Ui.u(10), Ui.u(5), Ui.u(10), Ui.u(5));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(Ui.uf(12));
        c.setBackground(d);
        return c;
    }

    private void paragraph(LinearLayout s, String text) {
        TextView p = body(text, 0.86f);
        s.addView(p, margins(0, 10, 0, 0));
    }

    private void subheading(LinearLayout s, String text) {
        TextView h = Ui.text(this, 12.5f, Ui.medium, 0.6f);
        h.setText(text.toUpperCase(Locale.getDefault()));
        h.setLetterSpacing(0.06f);
        s.addView(h, margins(0, 16, 0, 0));
    }

    /** Numbered steps with the number in a small circle, aligned with the first line of text. */
    private void steps(LinearLayout s, String... items) {
        for (int i = 0; i < items.length; i++) {
            LinearLayout row = new LinearLayout(this);
            TextView n = Ui.text(this, 12, Ui.medium, 1f);
            n.setText(String.valueOf(i + 1));
            n.setGravity(Gravity.CENTER);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(0x2EFFFFFF);
            n.setBackground(d);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(Ui.u(22), Ui.u(22));
            nlp.topMargin = Ui.u(1);
            row.addView(n, nlp);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, -2, 1f);
            blp.leftMargin = Ui.u(10);
            row.addView(body(items[i], 0.86f), blp);
            s.addView(row, margins(0, i == 0 ? 12 : 9, 0, 0));
        }
    }

    /** Amber box with a title: the one thing not to do. */
    private void warning(LinearLayout s, String title, String text) {
        LinearLayout box = vertical();
        box.setPadding(Ui.u(14), Ui.u(12), Ui.u(14), Ui.u(13));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Ui.withAlpha(AMBER, 0.13f));
        d.setStroke(Math.max(1, Ui.u(1)), Ui.withAlpha(AMBER, 0.45f));
        d.setCornerRadius(Ui.uf(12));
        box.setBackground(d);
        TextView t = Ui.text(this, 13, Ui.medium, 1f);
        t.setText("⚠  " + title.toUpperCase(Locale.getDefault()));
        t.setTextColor(AMBER);
        t.setLetterSpacing(0.05f);
        box.addView(t);
        box.addView(body(text, 0.92f), margins(0, 7, 0, 0));
        s.addView(box, margins(0, 16, 0, 0));
    }

    /** A highlighted fact (blue), e.g. the Telegram device limit. */
    private void note(LinearLayout s, String text) {
        TextView n = body(text, 1f);
        n.setPadding(Ui.u(14), Ui.u(11), Ui.u(14), Ui.u(12));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x262AABEE);
        d.setStroke(Math.max(1, Ui.u(1)), 0x732AABEE);
        d.setCornerRadius(Ui.uf(12));
        n.setBackground(d);
        SpannableStringBuilder b = new SpannableStringBuilder("ⓘ  " + text);
        b.setSpan(new ForegroundColorSpan(0xFF8FD3FF), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new TypefaceSpan("sans-serif-medium"), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        n.setText(b);
        s.addView(n, margins(0, 16, 0, 0));
    }

    private TextView body(String text, float alpha) {
        TextView p = Ui.text(this, 15, Ui.regular, alpha);
        p.setSingleLine(false);
        p.setEllipsize(null);
        p.setIncludeFontPadding(true);
        p.setLineSpacing(Ui.uf(3), 1f);
        p.setText(text);
        return p;
    }

    /** "No volver a mostrar" and "Continuar", with clear focus states for the remote. */
    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Room inside the bar for the focused button to grow without being cut (the column must keep
        // clipping, or the scrolled text would draw over this bar).
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        bar.setPadding(0, Ui.u(4), Ui.u(8), Ui.u(4));

        TextView hint = Ui.text(this, 13, Ui.regular, 0.5f);
        hint.setSingleLine(false);
        if (!manual) {
            hint.setText("Puedes volver a verlas con el botón «" + getString(R.string.recs_button)
                    + "» de la pantalla principal.");
        }
        bar.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));

        // The tick box: a square that fills when ticked, inside a pill that lights up with focus.
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(Ui.u(12), Ui.u(9), Ui.u(16), Ui.u(9));
        tickBox = Ui.text(this, 13, Ui.medium, 1f);
        tickBox.setGravity(Gravity.CENTER);
        t.addView(tickBox, new LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)));
        TextView tl = Ui.text(this, 15, Ui.medium, 1f);
        tl.setText(R.string.recs_dont_show);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = Ui.u(10);
        t.addView(tl, tlp);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setCornerRadius(Ui.uf(22));
        t.setBackground(tbg);
        focusable(t);
        t.setOnFocusChangeListener((v, has) -> paintTick(has));
        t.setOnClickListener(v -> {
            dontShow = !dontShow;
            paintTick(v.hasFocus());
        });
        tick = t;
        paintTick(false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = Ui.u(18);
        if (!manual) bar.addView(t, tp);

        TextView go = Ui.text(this, 16, Ui.medium, 1f);
        go.setText(R.string.recs_continue);
        go.setGravity(Gravity.CENTER);
        go.setPadding(Ui.u(28), Ui.u(11), Ui.u(28), Ui.u(11));
        GradientDrawable gbg = new GradientDrawable();
        gbg.setCornerRadius(Ui.uf(22));
        go.setBackground(gbg);
        focusable(go);
        go.setOnFocusChangeListener((v, has) -> {
            gbg.setColor(has ? 0xFFF5F5F7 : 0x26FFFFFF);
            go.setTextColor(has ? 0xFF07080B : Ui.TEXT);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        });
        go.setOnClickListener(v -> onContinue());
        gbg.setColor(0x26FFFFFF);
        if (schedule) {
            speedButton = Ui.text(this, 16, Ui.medium, 1f);
            speedButton.setText("Probar mi internet");
            speedButton.setGravity(Gravity.CENTER);
            speedButton.setPadding(Ui.u(24), Ui.u(11), Ui.u(24), Ui.u(11));
            final GradientDrawable sbg = new GradientDrawable();
            sbg.setCornerRadius(Ui.uf(22));
            sbg.setColor(0x267BD88F);
            speedButton.setBackground(sbg);
            focusable(speedButton);
            speedButton.setOnFocusChangeListener((v, has) -> {
                sbg.setColor(has ? 0xFFF5F5F7 : 0x267BD88F);
                speedButton.setTextColor(has ? 0xFF07080B : Ui.TEXT);
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
            });
            speedButton.setOnClickListener(v -> runSpeedTest());
            speedButton.setId(View.generateViewId());
            speedButton.setNextFocusLeftId(speedButton.getId());
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.leftMargin = Ui.u(14);
            bar.addView(speedButton, sp);
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-2, -2);
        gp.leftMargin = Ui.u(14);
        bar.addView(go, gp);

        // Left/right stay inside the bar, and "Continuar" has the focus first.
        t.setId(View.generateViewId());
        go.setId(View.generateViewId());
        t.setNextFocusLeftId(t.getId());
        if (manual) go.setNextFocusLeftId(speedButton != null ? speedButton.getId() : go.getId());
        go.setNextFocusRightId(go.getId());
        go.post(go::requestFocus);
        return bar;
    }

    private void paintTick(boolean focused) {
        ((GradientDrawable) tick.getBackground()).setColor(focused ? 0x33FFFFFF : 0x00000000);
        tick.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(150).start();
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(Ui.uf(5));
        if (dontShow) {
            box.setColor(0xFFF5F5F7);
            tickBox.setText("✓");
            tickBox.setTextColor(0xFF07080B);
        } else {
            box.setColor(0x00000000);
            box.setStroke(Math.max(2, Ui.u(1.6f)), 0xCCF5F5F7);
            tickBox.setText("");
        }
        tickBox.setBackground(box);
    }

    private static void focusable(View v) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        if (Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
    }

    private String installed(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (Exception e) {
            return null;
        }
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static LinearLayout.LayoutParams margins(float l, float t, float r, float b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(Ui.u(l), Ui.u(t), Ui.u(r), Ui.u(b));
        return lp;
    }
}
