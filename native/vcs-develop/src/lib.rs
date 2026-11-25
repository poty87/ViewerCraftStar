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

/// Sviluppa la foto aperta con le impostazioni (JSON di LightCraft) dentro un quadrato di lato `max_edge`.
/// Torna [larghezza, altezza, pixel ARGB…] (null se le impostazioni non si leggono).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_render<'a>(
    mut env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong, settings: JString<'a>, max_edge: jint,
) -> jintArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let photo = unsafe { &*(handle as *const Photo) };
    let Ok(json) = env.get_string(&settings) else { return std::ptr::null_mut() };
    let json: String = json.into();
    let Ok(s) = serde_json::from_str::<lightcraft_develop::DevelopSettings>(&json) else { return std::ptr::null_mut() };
    let edge = max_edge.max(16) as usize;
    let req = lightcraft_pipeline::RenderRequest::fit(edge, edge);
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

/// Chiude la foto aperta (libera la memoria).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_poty87_viewercraftstar_DevelopEngine_close<'a>(_env: JNIEnv<'a>, _c: JClass<'a>, handle: jlong) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Photo) });
    }
}
