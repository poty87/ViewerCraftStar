//! Il motore di sviluppo di LightCraft per ViewerCraftStar (JNI): apre una smart preview, la sviluppa con le
//! impostazioni di LightCraft (lo stesso JSON di `develop.get`) e restituisce i pixel. Solo processore: la scheda
//! grafica del telefono non ha retto nella prova del 25/11/2026 e il risultato è lo stesso (entro 1/255).
//!
//! Lato Kotlin: `io.github.poty87.viewercraftstar.DevelopEngine`.
use std::sync::Arc;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jintArray, jlong, jstring};

/// La versione di LightCraft con cui è compilato il motore (deve coincidere con quella del PC).
pub const LIGHTCRAFT_VERSION: &str = "0.21.1";

/// Una foto aperta: i pixel della smart preview, i dati del decoder e la cache delle fasi (un cursore che si muove
/// ricalcola solo le fasi che cambiano).
struct Photo {
    src: Arc<lightcraft_raster::Rgb32f>,
    info: lightcraft_pipeline::SourceInfo,
    cache: lightcraft_pipeline::StageCache,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_version<'a>(env: JNIEnv<'a>, _c: JClass<'a>) -> jstring {
    env.new_string(LIGHTCRAFT_VERSION).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Registra un profilo LUT (`lut:…`) dal testo del suo file `.cube`. True se letto.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_registerLut<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, id: JString<'a>, cube: JString<'a>,
) -> jboolean {
    let (Ok(id), Ok(cube)) = (env.get_string(&id), env.get_string(&cube)) else { return 0 };
    let (id, cube): (String, String) = (id.into(), cube.into());
    match lightcraft_pipeline::lut::Lut3d::parse_cube(&cube) {
        Ok(l) => {
            lightcraft_pipeline::lut::register(&id, l);
            1
        }
        Err(_) => 0,
    }
}

/// Apre una smart preview (`.lcsp`). Torna un riferimento (0 se il file non si legge).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_open<'a>(
    env: JNIEnv<'a>, _c: JClass<'a>, bytes: JByteArray<'a>,
) -> jlong {
    let Ok(b) = env.convert_byte_array(&bytes) else { return 0 };
    match lightcraft_engine::smart::decode(&b) {
        Ok((img, _tone, info)) => {
            let p = Photo { src: Arc::new(img), info: info.unwrap_or_default(), cache: Default::default() };
            Box::into_raw(Box::new(p)) as jlong
        }
        Err(_) => 0,
    }
}

/// Apre il file originale di una foto raw (DNG…) decodificandolo con il profilo della fotocamera `dcp` (il file .dcp;
/// vuoto = nessuno, i colori del file), dentro un quadrato di lato `max_edge`. In LightCraft il profilo DCP si applica
/// proprio qui, leggendo il raw: le smart preview sono sempre fatte senza (`library.smartPreviews`), quindi per una foto
/// con un profilo DCP serve l'originale. Torna un riferimento come `open` (0 se il file o il profilo non si leggono).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_openRaw<'a>(
    env: JNIEnv<'a>, _c: JClass<'a>, bytes: JByteArray<'a>, dcp: JByteArray<'a>, max_edge: jint,
) -> jlong {
    let Ok(b) = env.convert_byte_array(&bytes) else { return 0 };
    let d = if dcp.is_null() { Vec::new() } else { env.convert_byte_array(&dcp).unwrap_or_default() };
    let profile = if d.is_empty() {
        None
    } else {
        match lightcraft_engine::media::LoadedCameraProfile::parse(&d) {
            Ok(p) => Some(p),
            Err(_) => return 0,
        }
    };
    let edge = max_edge.max(16) as usize;
    match lightcraft_engine::files::load_vec(b, edge, lightcraft_engine::media::RawDecode { dcp: profile.as_ref().map(|p| p.profile.as_ref()), outliers: false }) {
        Ok((img, info)) => {
            let p = Photo { src: Arc::new(img), info, cache: Default::default() };
            Box::into_raw(Box::new(p)) as jlong
        }
        Err(_) => 0,
    }
}

/// Sviluppa la foto aperta con le impostazioni (JSON di LightCraft) dentro un quadrato di lato `max_edge`;
/// `apply_crop` falso = la foto intera senza ritaglio né raddrizzamento (mentre si ritaglia, come in LightCraft).
/// Torna [larghezza, altezza, pixel ARGB…] (null se le impostazioni non si leggono).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_render<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong, settings: JString<'a>, max_edge: jint, apply_crop: jboolean,
) -> jintArray {
    render_with(&mut env, handle, &settings, max_edge, apply_crop, -1)
}

/// Come `render`, con la maschera `mask_id` (l'id di una maschera delle impostazioni) disegnata sopra in rosso, come
/// la sovrapposizione di LightCraft; `mask_id` < 0 = nessuna.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_renderMask<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong, settings: JString<'a>, max_edge: jint, apply_crop: jboolean, mask_id: jint,
) -> jintArray {
    render_with(&mut env, handle, &settings, max_edge, apply_crop, mask_id)
}

fn render_with<'a>(env: &mut JNIEnv<'a>, handle: jlong, settings: &JString<'a>, max_edge: jint, apply_crop: jboolean, mask_id: jint) -> jintArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let photo = unsafe { &*(handle as *const Photo) };
    let Ok(json) = env.get_string(settings) else { return std::ptr::null_mut() };
    let json: String = json.into();
    let Ok(s) = serde_json::from_str::<lightcraft_develop::DevelopSettings>(&json) else { return std::ptr::null_mut() };
    let edge = max_edge.max(16) as usize;
    let mut req = lightcraft_pipeline::RenderRequest::fit(edge, edge);
    req.apply_crop = apply_crop != 0;
    if mask_id >= 0 {
        req.overlay = lightcraft_pipeline::Overlay::Mask {
            id: mask_id as u16,
            view: lightcraft_pipeline::MaskView::Color,
            color: [235, 64, 52],
            opacity: 50,
        };
    }
    let r = lightcraft_engine::media::develop(&photo.src, &photo.info, &s, &req, Some(&photo.cache), false);
    let img = r.image;
    let mut out: Vec<i32> = Vec::with_capacity(2 + img.data.len());
    out.push(img.width as i32);
    out.push(img.height as i32);
    out.extend(img.data.iter().map(|p| i32::from_be_bytes([p[3], p[0], p[1], p[2]])));
    let Ok(arr) = env.new_int_array(out.len() as i32) else { return std::ptr::null_mut() };
    if env.set_int_array_region(&arr, 0, &out).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

/// Converte punti (x, y, x, y…) tra la foto sviluppata con `settings` (`out_w` x `out_h` pixel, `apply_crop` come nello
/// sviluppo) e le coordinate delle maschere di LightCraft (normalizzate sulla foto com'è scattata, prima di obiettivo,
/// ritaglio e raddrizzamento: `Mask::on_photo`). `to_mask` vero: dai pixel alle maschere; falso: il contrario (NaN per
/// un punto che non ha posto nella foto). Con la `Frame` di LightCraft, così ritaglio, raddrizzamento e correzioni
/// dell'obiettivo valgono come nello sviluppo.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_maskMap<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong, settings: JString<'a>, apply_crop: jboolean, out_w: jint, out_h: jint,
    pts: jni::objects::JDoubleArray<'a>, to_mask: jboolean,
) -> jni::sys::jdoubleArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let photo = unsafe { &*(handle as *const Photo) };
    let Ok(json) = env.get_string(&settings) else { return std::ptr::null_mut() };
    let json: String = json.into();
    let Ok(s) = serde_json::from_str::<lightcraft_develop::DevelopSettings>(&json) else { return std::ptr::null_mut() };
    let Ok(n) = env.get_array_length(&pts) else { return std::ptr::null_mut() };
    let mut v = vec![0f64; n as usize];
    if env.get_double_array_region(&pts, 0, &mut v).is_err() {
        return std::ptr::null_mut();
    }
    let frame = lightcraft_pipeline::geometry::Frame::with_lens(photo.src.width, photo.src.height, &s, apply_crop != 0, photo.info.lens.as_ref());
    let f = frame.for_mask(true);
    let (w, h) = (out_w.max(1) as usize, out_h.max(1) as usize);
    let mut out = Vec::with_capacity(v.len());
    if to_mask != 0 {
        let m = f.out_to_mask(w, h);
        for c in v.chunks(2) {
            let p = m.apply(lightcraft_geom::Point::new(c[0], c.get(1).copied().unwrap_or(0.0)));
            out.extend([p.x, p.y]);
        }
    } else {
        let g = f.mask_to_out(w, h);
        for c in v.chunks(2) {
            match g(lightcraft_geom::Point::new(c[0], c.get(1).copied().unwrap_or(0.0))) {
                Some(p) => out.extend([p.x, p.y]),
                None => out.extend([f64::NAN, f64::NAN]),
            }
        }
    }
    let Ok(arr) = env.new_double_array(out.len() as i32) else { return std::ptr::null_mut() };
    if env.set_double_array_region(&arr, 0, &out).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

/// Dà alla foto aperta la correzione dell'obiettivo trovata da LightCraft nel suo database (JSON di `EmbeddedLens`,
/// pubblicato da PresetCraftAppLicator): le smart preview hanno solo quella scritta nel file. Non tocca una foto che ha
/// già la correzione del file. True se ora la foto ha una correzione.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_setLens<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong, lens: JString<'a>,
) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let photo = unsafe { &mut *(handle as *mut Photo) };
    if photo.info.lens.is_some() {
        return 1;
    }
    let Ok(text) = env.get_string(&lens) else { return 0 };
    let text: String = text.into();
    match serde_json::from_str::<lightcraft_develop::EmbeddedLens>(&text) {
        Ok(l) => {
            photo.info.lens = Some(l);
            photo.cache = Default::default(); // la geometria cambia: le fasi in memoria non valgono più
            1
        }
        Err(_) => 0,
    }
}

/// Da dove viene la correzione dell'obiettivo della foto aperta: "file", "database" o "" (nessuna).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_lensSource<'a>(env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong) -> jstring {
    let s = if handle == 0 {
        ""
    } else {
        let photo = unsafe { &*(handle as *const Photo) };
        match &photo.info.lens {
            Some(l) if l.source == lightcraft_develop::LensSource::Database => "database",
            Some(_) => "file",
            None => "",
        }
    };
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Il colore che una maschera per colore campiona nel punto (x, y) della foto sviluppata con `settings` (`out_w` x
/// `out_h` pixel, ritagliata): OkLab come lo confronta LightCraft (`color_range_sample`, media di 3 x 3 pixel).
/// Torna [L, a, b] o null fuori dalla foto.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_colorSample<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong, settings: JString<'a>, out_w: jint, out_h: jint, x: f64, y: f64,
) -> jni::sys::jdoubleArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let photo = unsafe { &*(handle as *const Photo) };
    let Ok(json) = env.get_string(&settings) else { return std::ptr::null_mut() };
    let json: String = json.into();
    let Ok(s) = serde_json::from_str::<lightcraft_develop::DevelopSettings>(&json) else { return std::ptr::null_mut() };
    let (w, h) = (out_w.max(1) as usize, out_h.max(1) as usize);
    let frame = lightcraft_pipeline::geometry::Frame::with_lens(photo.src.width, photo.src.height, &s, true, photo.info.lens.as_ref());
    let p = frame.out_to_norm(w, h).apply(lightcraft_geom::Point::new(x, y));
    let req = lightcraft_pipeline::RenderRequest::fit(w.max(h), w.max(h));
    let Some(lab) = lightcraft_pipeline::color_range_sample(&photo.src, &photo.info, &s, &req, p) else { return std::ptr::null_mut() };
    let Ok(arr) = env.new_double_array(3) else { return std::ptr::null_mut() };
    if env.set_double_array_region(&arr, 0, &lab).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

/// Chiude la foto aperta (libera la memoria).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_close<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Photo) });
    }
}

/// Larghezza e altezza della foto aperta (già girata come nel file).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_size<'a>(env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong) -> jintArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let photo = unsafe { &*(handle as *const Photo) };
    let v = [photo.src.width as i32, photo.src.height as i32];
    let Ok(arr) = env.new_int_array(2) else { return std::ptr::null_mut() };
    if env.set_int_array_region(&arr, 0, &v).is_err() {
        return std::ptr::null_mut();
    }
    arr.into_raw()
}

fn geometry<'a>(env: &mut JNIEnv<'a>, json: &JString<'a>) -> Option<lightcraft_geom::CropGeometry> {
    let text: String = env.get_string(json).ok()?.into();
    serde_json::from_str(&text).ok()
}

fn geometry_out<'a>(env: &JNIEnv<'a>, g: &lightcraft_geom::CropGeometry) -> jstring {
    serde_json::to_string(g).ok().and_then(|t| env.new_string(t).ok()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Il ritaglio girato di `angle` gradi (come il cursore Raddrizza di LightCraft: resta dentro la foto).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_cropWithAngle<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, geom: JString<'a>, angle: f64, w: f64, h: f64,
) -> jstring {
    match geometry(&mut env, &geom) {
        Some(g) => geometry_out(&env, &g.with_angle(angle, w, h)),
        None => std::ptr::null_mut(),
    }
}

/// Il ritaglio più grande con il rapporto `aspect` (larghezza/altezza; 0 = quello della foto) a `angle` gradi.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_cropFit<'a>(
    env: JNIEnv<'a>, _c: JClass<'a>, w: f64, h: f64, angle: f64, aspect: f64,
) -> jstring {
    let g = lightcraft_geom::crop_fit_angle(w, h, angle, (aspect > 0.0).then_some(aspect));
    geometry_out(&env, &g)
}

/// Il ritaglio spostato (e se serve rimpicciolito) finché sta dentro la foto girata.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_cropConstrained<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, geom: JString<'a>, w: f64, h: f64,
) -> jstring {
    match geometry(&mut env, &geom) {
        Some(g) => geometry_out(&env, &g.constrained(w, h)),
        None => std::ptr::null_mut(),
    }
}

/// Trascina la maniglia `handle` (0..3 angoli in senso orario da in alto a sinistra, 4..7 lati alto/destra/basso/
/// sinistra, 8 = sposta) da `grab` a `pointer`, punti nel riquadro raddrizzato in coordinate 0..1 (la vista del
/// ritaglio mostra la foto raddrizzata, come LightCraft), con il modello di LightCraft: resta dentro la foto, i lati
/// opposti restano fermi, `aspect` > 0 = rapporto larghezza/altezza bloccato.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_cropDrag<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, geom: JString<'a>, handle: jint, gx: f64, gy: f64, px: f64, py: f64, w: f64, h: f64,
    aspect: f64,
) -> jstring {
    let Some(g) = geometry(&mut env, &geom) else { return std::ptr::null_mut() };
    let Some(hd) = lightcraft_geom::CropHandle::from_index(handle as u8) else { return std::ptr::null_mut() };
    let p = |x: f64, y: f64| lightcraft_geom::Point::new(x, y);
    let out = lightcraft_geom::drag_crop(g, hd, p(gx, gy), p(px, py), w, h, (aspect > 0.0).then_some(aspect));
    geometry_out(&env, &out)
}
