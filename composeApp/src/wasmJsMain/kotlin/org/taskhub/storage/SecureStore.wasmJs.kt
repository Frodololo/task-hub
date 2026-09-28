// Capa de persistencia (storage/), implementación web (wasmJs) del contrato
// [SecureStore] (`actual` de la `expect fun createSecureStore()` común).
//
// Cifrado AES-256-CTR con clave efímera generada en tiempo de carga del
// módulo via Web Crypto API (`crypto.getRandomValues`). La clave solo existe
// en esta variable de Kotlin (no se persiste en localStorage), por lo que se
// borra al recargar la página — resistente a sniffing de localStorage en
// máquinas compartidas mientras la pestaña está abierta (panel v14, hallazgo 5).
// Se usa CTR (no GCM) porque SubtleCrypto.encrypt es asíncrono (Promise) y no
// se puede llamar desde código síncrono en Kotlin/Wasm; AES-256-CTR con clave
// efímera proporciona el mismo nivel de confidencialidad.

package org.taskhub.storage

import kotlinx.browser.localStorage

private const val KEY_PREFIX = "taskhub_secure_"

// Clave AES-256 efímera (32 bytes), generada una vez en la carga del módulo
// via Web Crypto API (`crypto.getRandomValues`). No se persiste: se pierde
// al recargar la página. Se pasa a JS como Uint8Array directamente.
private val ephemeralKey: String = jsRandomBytes(32)

/** Genera [length] bytes aleatorios via Web Crypto API (síncrono, CSPRNG). */
@JsFun("(l) => { const a = new Uint8Array(l); crypto.getRandomValues(a); return String.fromCharCode(...a); }")
private external fun jsRandomBytes(length: Int): String

/**
 * AES-256-CTR encrypt: toma la clave como Uint8Array, el texto plano como
 * string JS, genera IV de 12 bytes via crypto.getRandomValues, cifra,
 * devuelve base64(IV + ciphertext). CTR es simétrico.
 */
@JsFun("""(key, plainText) => {
    const K = new Uint8Array(key.length); for(let i=0;i<key.length;i++) K[i]=key.charCodeAt(i);
    const D = new TextEncoder().encode(plainText);
    // AES S-box
    const S=new Uint8Array([99,124,119,123,242,107,111,197,48,1,103,43,254,215,171,118,202,130,201,125,250,89,71,240,173,212,162,175,156,164,114,192,183,253,147,38,54,63,247,204,52,165,229,241,113,216,49,21,4,199,35,195,24,150,5,154,7,18,128,226,235,39,178,117,9,131,44,26,27,110,90,160,82,59,214,179,41,227,47,132,83,209,0,237,32,252,177,91,106,203,190,57,74,76,88,207,208,239,170,251,67,77,51,133,69,249,2,127,80,60,159,168,81,163,64,143,146,157,56,245,188,182,218,33,16,255,243,210,205,12,19,236,95,151,68,23,196,167,126,61,100,93,25,115,96,129,79,220,34,42,144,136,70,238,184,20,222,94,11,219,224,50,58,10,73,6,36,92,194,211,172,98,145,149,228,121,231,200,55,109,141,213,78,169,108,86,244,234,101,122,174,8,186,120,37,46,28,166,180,198,232,221,116,31,75,189,139,138,112,62,181,102,72,3,246,14,97,53,87,185,134,193,29,158,225,248,150,17,105,217,142,148,155,30,135,233,206,85,40,223,140,161,137,13,191,230,66,104,65,153,45,15,176,84,187,22]);
    // Expand key: 32 bytes -> 60 words
    const w = new Uint32Array(60);
    for (let i=0; i<8; i++) w[i]=(K[4*i]<<24)|(K[4*i+1]<<16)|(K[4*i+2]<<8)|K[4*i+3];
    const R=[1,2,4,8,16,32,64,128,27,54,108,216,171,77];
    for (let i=8; i<60; i++) {
        let t = w[i-1];
        if (i%8===0) { let r=(t<<8)|(t>>>24); t=(S[r>>>24]<<24)|(S[(r>>16)&255]<<16)|(S[(r>>8)&255]<<8)|S[r&255]^(R[i/8-1]<<24); }
        else if (i%8===4) t=(S[t>>>24]<<24)|(S[(t>>16)&255]<<16)|(S[(t>>8)&255]<<8)|S[t&255];
        w[i]=w[i-8]^t;
    }
    function enc(b) {
        let s=b.slice();
        for (let i=0;i<4;i++) s[i]^=w[i];
        for (let r=1;r<=14;r++) {
            s[0]=(S[s[0]>>>24]<<24)|(S[(s[0]>>16)&255]<<16)|(S[(s[0]>>8)&255]<<8)|S[s[0]&255];
            s[1]=(S[s[1]>>>24]<<24)|(S[(s[1]>>16)&255]<<16)|(S[(s[1]>>8)&255]<<8)|S[s[1]&255];
            s[2]=(S[s[2]>>>24]<<24)|(S[(s[2]>>16)&255]<<16)|(S[(s[2]>>8)&255]<<8)|S[s[2]&255];
            s[3]=(S[s[3]>>>24]<<24)|(S[(s[3]>>16)&255]<<16)|(S[(s[3]>>8)&255]<<8)|S[s[3]&255];
            const t0=s[0],t1=s[1],t2=s[2],t3=s[3];
            s[0]= (t0&0xff000000)|((t1<<8)&0x00ff0000)|((t2<<16)&0x0000ff00)|((t3>>>24)&0x000000ff);
            s[1]= (t1&0xff000000)|((t2<<8)&0x00ff0000)|((t3<<16)&0x0000ff00)|((t0>>>24)&0x000000ff);
            s[2]= (t2&0xff000000)|((t3<<8)&0x00ff0000)|((t0<<16)&0x0000ff00)|((t1>>>24)&0x000000ff);
            s[3]= (t3&0xff000000)|((t0<<8)&0x00ff0000)|((t1<<16)&0x0000ff00)|((t2>>>24)&0x000000ff);
            if (r<14) {
                for (let c=0;c<4;c++) {
                    const a=s[c]>>>24,b=(s[c]>>16)&255,g=(s[c]>>8)&255,d=s[c]&255;
                    const a2=a<<1^((a>>7)*0x1b),b2=b<<1^((b>>7)*0x1b),g2=g<<1^((g>>7)*0x1b),d2=d<<1^((d>>7)*0x1b);
                    s[c]=((a2^b^g^d^b2)<<24)|((a^b2^g^d^g2)<<16)|((a^b^g2^d^d2)<<8)|(a2^a^b^g^d2^d);
                }
            }
            for (let i=0;i<4;i++) s[i]^=w[r*4+i];
        }
        return s;
    }
    // CTR mode
    const iv = new Uint8Array(12);
    crypto.getRandomValues(iv);
    const ctr = new Uint8Array(16);
    ctr.set(iv); ctr[15]=1;
    const out = new Uint8Array(D.length);
    for (let i=0;i<D.length;i+=16) {
        const ks = enc([(ctr[0]<<24)|(ctr[1]<<16)|(ctr[2]<<8)|ctr[3],(ctr[4]<<24)|(ctr[5]<<16)|(ctr[6]<<8)|ctr[7],(ctr[8]<<24)|(ctr[9]<<16)|(ctr[10]<<8)|ctr[11],(ctr[12]<<24)|(ctr[13]<<16)|(ctr[14]<<8)|ctr[15]]);
        for (let j=0;j<16&&i+j<D.length;j++) out[i+j]=D[i+j]^(ks[j>>>2]>>>(24-(j&3)*8)&255);
        for (let j=15;j>=12;j--) if (++ctr[j]!==0) break;
    }
    const combined = new Uint8Array(12+out.length);
    combined.set(iv); combined.set(out,12);
    let bin=''; for(let i=0;i<combined.length;i++) bin+=String.fromCharCode(combined[i]);
    return btoa(bin);
}""")
private external fun jsAesCtrEncrypt(key: String, plainText: String): String

/**
 * AES-256-CTR decrypt: extrae IV (primeros 12 bytes) del payload base64,
 * descifra, devuelve el texto plano como string JS (UTF-8). Devuelve string
 * vacío si el payload es inválido.
 */
@JsFun("""(key, encB64) => {
    const K = new Uint8Array(key.length); for(let i=0;i<key.length;i++) K[i]=key.charCodeAt(i);
    const enc = Uint8Array.from(atob(encB64),c=>c.charCodeAt(0));
    if (enc.length<12) return '';
    const iv = enc.slice(0,12); const D = enc.slice(12);
    // AES S-box
    const S=new Uint8Array([99,124,119,123,242,107,111,197,48,1,103,43,254,215,171,118,202,130,201,125,250,89,71,240,173,212,162,175,156,164,114,192,183,253,147,38,54,63,247,204,52,165,229,241,113,216,49,21,4,199,35,195,24,150,5,154,7,18,128,226,235,39,178,117,9,131,44,26,27,110,90,160,82,59,214,179,41,227,47,132,83,209,0,237,32,252,177,91,106,203,190,57,74,76,88,207,208,239,170,251,67,77,51,133,69,249,2,127,80,60,159,168,81,163,64,143,146,157,56,245,188,182,218,33,16,255,243,210,205,12,19,236,95,151,68,23,196,167,126,61,100,93,25,115,96,129,79,220,34,42,144,136,70,238,184,20,222,94,11,219,224,50,58,10,73,6,36,92,194,211,172,98,145,149,228,121,231,200,55,109,141,213,78,169,108,86,244,234,101,122,174,8,186,120,37,46,28,166,180,198,232,221,116,31,75,189,139,138,112,62,181,102,72,3,246,14,97,53,87,185,134,193,29,158,225,248,150,17,105,217,142,148,155,30,135,233,206,85,40,223,140,161,137,13,191,230,66,104,65,153,45,15,176,84,187,22]);
    // Expand key
    const w = new Uint32Array(60);
    for (let i=0; i<8; i++) w[i]=(K[4*i]<<24)|(K[4*i+1]<<16)|(K[4*i+2]<<8)|K[4*i+3];
    const R=[1,2,4,8,16,32,64,128,27,54,108,216,171,77];
    for (let i=8; i<60; i++) {
        let t = w[i-1];
        if (i%8===0) { let r=(t<<8)|(t>>>24); t=(S[r>>>24]<<24)|(S[(r>>16)&255]<<16)|(S[(r>>8)&255]<<8)|S[r&255]^(R[i/8-1]<<24); }
        else if (i%8===4) t=(S[t>>>24]<<24)|(S[(t>>16)&255]<<16)|(S[(t>>8)&255]<<8)|S[t&255];
        w[i]=w[i-8]^t;
    }
    function encDecrypt(b) {
        let s=b.slice();
        for (let i=0;i<4;i++) s[i]^=w[i];
        for (let r=1;r<=14;r++) {
            s[0]=(S[s[0]>>>24]<<24)|(S[(s[0]>>16)&255]<<16)|(S[(s[0]>>8)&255]<<8)|S[s[0]&255];
            s[1]=(S[s[1]>>>24]<<24)|(S[(s[1]>>16)&255]<<16)|(S[(s[1]>>8)&255]<<8)|S[s[1]&255];
            s[2]=(S[s[2]>>>24]<<24)|(S[(s[2]>>16)&255]<<16)|(S[(s[2]>>8)&255]<<8)|S[s[2]&255];
            s[3]=(S[s[3]>>>24]<<24)|(S[(s[3]>>16)&255]<<16)|(S[(s[3]>>8)&255]<<8)|S[s[3]&255];
            const t0=s[0],t1=s[1],t2=s[2],t3=s[3];
            s[0]= (t0&0xff000000)|((t1<<8)&0x00ff0000)|((t2<<16)&0x0000ff00)|((t3>>>24)&0x000000ff);
            s[1]= (t1&0xff000000)|((t2<<8)&0x00ff0000)|((t3<<16)&0x0000ff00)|((t0>>>24)&0x000000ff);
            s[2]= (t2&0xff000000)|((t3<<8)&0x00ff0000)|((t0<<16)&0x0000ff00)|((t1>>>24)&0x000000ff);
            s[3]= (t3&0xff000000)|((t0<<8)&0x00ff0000)|((t1<<16)&0x0000ff00)|((t2>>>24)&0x000000ff);
            if (r<14) {
                for (let c=0;c<4;c++) {
                    const a=s[c]>>>24,b=(s[c]>>16)&255,g=(s[c]>>8)&255,d=s[c]&255;
                    const a2=a<<1^((a>>7)*0x1b),b2=b<<1^((b>>7)*0x1b),g2=g<<1^((g>>7)*0x1b),d2=d<<1^((d>>7)*0x1b);
                    s[c]=((a2^b^g^d^b2)<<24)|((a^b2^g^d^g2)<<16)|((a^b^g2^d^d2)<<8)|(a2^a^b^g^d2^d);
                }
            }
            for (let i=0;i<4;i++) s[i]^=w[r*4+i];
        }
        return s;
    }
    const ctr = new Uint8Array(16);
    ctr.set(iv); ctr[15]=1;
    const out = new Uint8Array(D.length);
    for (let i=0;i<D.length;i+=16) {
        const ks = encDecrypt([(ctr[0]<<24)|(ctr[1]<<16)|(ctr[2]<<8)|ctr[3],(ctr[4]<<24)|(ctr[5]<<16)|(ctr[6]<<8)|ctr[7],(ctr[8]<<24)|(ctr[9]<<16)|(ctr[10]<<8)|ctr[11],(ctr[12]<<24)|(ctr[13]<<16)|(ctr[14]<<8)|ctr[15]]);
        for (let j=0;j<16&&i+j<D.length;j++) out[i+j]=D[i+j]^(ks[j>>>2]>>>(24-(j&3)*8)&255);
        for (let j=15;j>=12;j--) if (++ctr[j]!==0) break;
    }
    // fatal:true: una clave incorrecta (p. ej. tras recargar la página, con
    // una ephemeralKey nueva) produce bytes que casi nunca son UTF-8 válido —
    // sin fatal:true, TextDecoder los reemplaza en silencio por el carácter
    // U+FFFD y devuelve basura como si fuera un valor real; con fatal:true
    // lanza, y el catch de Kotlin en getString() ya existente la convierte en
    // `null` limpio (panel v15, oleada 2, hallazgo estrella).
    return new TextDecoder('utf-8', {fatal: true}).decode(out);
}""")
private external fun jsAesCtrDecrypt(key: String, encB64: String): String

/**
 * Ver [SecureStore]. El navegador no expone un keychain de sistema: en vez de
 * `localStorage` plano (el hueco documentado en resúmenes anteriores), se
 * cifran los valores con AES-256-CTR usando una clave efímera generada via
 * Web Crypto API (`crypto.getRandomValues`). La clave no se persiste, así que
 * se borra al recargar la página — resistente a sniffing de localStorage
 * mientras la pestaña está abierta (panel v14, hallazgo 5).
 */
actual fun createSecureStore(): SecureStore = WasmJsSecureStore()

private class WasmJsSecureStore : SecureStore {
    override fun getString(key: String): String? {
        return try {
            val stored = localStorage.getItem(KEY_PREFIX + key) ?: return null
            // Datos legacy (sin prefijo v1) se devuelven tal cual (migración)
            if (!stored.startsWith("v1:")) { return stored }
            jsAesCtrDecrypt(ephemeralKey, stored.removePrefix("v1:"))
        } catch (_: Throwable) {
            null
        }
    }

    override fun putString(key: String, value: String) {
        try {
            val encrypted = "v1:" + jsAesCtrEncrypt(ephemeralKey, value)
            localStorage.setItem(KEY_PREFIX + key, encrypted)
        } catch (_: Throwable) {
            // Best-effort: sin storage disponible no hay dónde persistir la
            // sesión, pero no debe tumbar el flujo que la está guardando.
        }
    }

    override fun remove(key: String) {
        try {
            localStorage.removeItem(KEY_PREFIX + key)
        } catch (_: Throwable) {
            // Best-effort, mismo motivo que putString.
        }
    }
}