package cn.aufe.campus.auth

import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * WebView 混合登录状态机：
 * 1) VPN 门户自动登录（页面自带 RSA 加密，直接模拟填写）
 * 2) 统一身份认证 CAS 登录
 * 3) 教务系统 SSO 验证
 * 4) 爱安财 token 读取
 */
class LoginController(
    private val authManager: AuthManager,
    private val onStep: (step: Int, label: String) -> Unit,
    private val onSuccess: () -> Unit,
    private val onError: (message: String) -> Unit
) {

    companion object {
        const val STEP_VPN = 0
        const val STEP_CAS = 1
        const val STEP_JW = 2
        const val STEP_DEKT = 3

        private val VPN_PROBE = """
            JSON.stringify({hash:location.hash,url:location.href,
              hasUser:!!document.querySelector('input.input-txt[type=text]'),
              hasPwd:!!document.getElementById('loginPwd'),
              bodyLen:document.body?document.body.innerText.length:0,
              loginErr:(document.body.innerText.match(/(密码错误|账号错误|用户名或密码|登录失败|错误)/)||[])[1]||''})
        """.trimIndent()

        private val CAS_PROBE = """
            JSON.stringify({url:location.href,
              hasUser:!!document.querySelector('input[name=username]'),
              hasPwd:!!document.querySelector('input[name=password]'),
              title:document.title,
              bodyLen:document.body?document.body.innerText.length:0,
              errText:(document.body.innerText.match(/(密码错误|账号错误|用户名或密码错误|登录失败|无效的凭据|bad credentials)/)||[])[1]||''})
        """.trimIndent()

        private val PAGE_PROBE = """
            JSON.stringify({url:location.href,title:document.title,
              bodyLen:document.body?document.body.innerText.length:0,
              text:document.body?document.body.innerText.slice(0,400):''})
        """.trimIndent()
    }

    private var vpnFillDone = false
    private var casFillDone = false

    suspend fun run(webView: WebView, vpnUser: String, vpnPass: String, jwUser: String, jwPass: String) {
        try {
            // ===== 步骤 1：VPN 门户 =====
            onStep(STEP_VPN, "正在登录 VPN 门户…")
            webView.loadUrl(cn.aufe.campus.data.net.Endpoints.VPN_LOGIN_PAGE)
            var ok = poll(90_000, 800) {
                val s = evalJson(webView, VPN_PROBE)
                val hash = s["hash"] ?: ""
                if (hash.contains("service") || hash.contains("index")) return@poll true
                val hasForm = s["hasUser"] == "true" && s["hasPwd"] == "true"
                if (hasForm && !vpnFillDone) {
                    vpnFillDone = true
                    fillVpnForm(webView, vpnUser, vpnPass)
                    return@poll false
                }
                false
            }
            if (!ok) {
                // 有的门户登录后 hash 不变化，给一次宽限再继续，由后续步骤校验
                delay(3000)
            }

            // ===== 步骤 2：统一身份认证（CAS）=====
            onStep(STEP_CAS, "正在登录统一身份认证…")
            casFillDone = false
            webView.loadUrl(cn.aufe.campus.data.net.Endpoints.SMART_CAMPUS)
            ok = poll(90_000, 800) {
                val s = evalJson(webView, CAS_PROBE)
                val url = s["url"] ?: ""
                val onCas = url.contains("cas/login", true) || url.contains("uaap", true)
                if (onCas && s["hasUser"] == "true" && !casFillDone) {
                    casFillDone = true
                    fillCasForm(webView, jwUser, jwPass)
                    return@poll false
                }
                if (onCas) return@poll false
                val bodyLen = (s["bodyLen"] ?: "0").toIntOrNull() ?: 0
                // 落到 VPN 登录页说明 VPN 未登录成功
                if (url.contains("vpn") && url.contains("login")) return@poll false
                bodyLen > 200
            }
            if (!ok) error("统一身份认证登录失败，请检查账号密码")

            // ===== 步骤 3：教务系统验证（SSO）=====
            onStep(STEP_JW, "正在验证教务系统会话…")
            casFillDone = false
            webView.loadUrl(cn.aufe.campus.data.net.Endpoints.JW_SCHEDULE_PAGE)
            ok = poll(90_000, 800) {
                val s = evalJson(webView, PAGE_PROBE)
                val url = s["url"] ?: ""
                val text = s["text"] ?: ""
                if (url.contains("cas/login", true) && s["hasUser"] == "true" && !casFillDone) {
                    casFillDone = true
                    fillCasForm(webView, jwUser, jwPass)
                    return@poll false
                }
                text.contains("欢迎您") || text.contains("课程表") || url.contains("thisSemesterCurriculum")
            }
            if (!ok) error("教务系统会话验证失败")

            // ===== 步骤 4：爱安财 token =====
            onStep(STEP_DEKT, "正在获取爱安财成绩单授权…")
            webView.loadUrl(cn.aufe.campus.data.net.Endpoints.DEKT_CORE_LOGIN)
            var token = ""
            ok = poll(60_000, 1000) {
                token = evalString(webView, "localStorage.getItem('token')||''").trim().trim('"')
                token.isNotEmpty() && token != "null"
            }
            if (!ok || token.isBlank()) error("爱安财平台授权失败")

            // ===== 导出 Cookie 与 token =====
            onStep(STEP_DEKT, "正在保存会话…")
            val count = authManager.cookieStore.importFromWebView(
                android.webkit.CookieManager.getInstance(),
                cn.aufe.campus.data.net.Endpoints.COOKIE_URLS
            )
            authManager.onLoginSuccess(token)
            if (count == 0) error("会话保存失败，请重试")
            onSuccess()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: LoginException) {
            onError(e.message ?: "登录失败")
        } catch (e: Exception) {
            onError(e.message ?: "登录过程发生异常")
        }
    }

    private class LoginException(message: String) : Exception(message)

    private fun error(msg: String): Nothing = throw LoginException(msg)

    // ---------- 表单填充 ----------

    private suspend fun fillVpnForm(webView: WebView, user: String, pass: String) {
        val js = """
            (function(){
              var u=document.querySelector('input.input-txt[type=text]');
              var p=document.getElementById('loginPwd');
              if(!u||!p) return 'noform';
              var setV=function(el,v){el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))};
              setV(u,${jsStr(user)}); setV(p,${jsStr(pass)});
              var cb=document.querySelector('input[type=checkbox]');
              if(cb && !cb.checked) cb.click();
              return 'filled';
            })()
        """.trimIndent()
        evalRaw(webView, js)
        delay(1200)
        val click = """
            (function(){
              var btns=Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){return b.offsetParent});
              var b=null;
              for(var i=0;i<btns.length;i++){ if(/确定|Log In|登录/.test(btns[i].innerText)) { b=btns[i]; break; } }
              if(!b && btns.length>0) b=btns[0];
              if(b){ b.click(); return 'clicked'; }
              return 'nobutton';
            })()
        """.trimIndent()
        evalRaw(webView, click)
    }

    private suspend fun fillCasForm(webView: WebView, user: String, pass: String) {
        val js = """
            (function(){
              var u=document.querySelector('input[name=username]');
              var p=document.querySelector('input[name=password]');
              if(!u||!p) return 'noform';
              var setV=function(el,v){el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}))};
              setV(u,${jsStr(user)}); setV(p,${jsStr(pass)});
              var btn=document.querySelector('input[type=submit]')||document.querySelector('button[type=submit]');
              if(!btn){
                var bs=Array.prototype.slice.call(document.querySelectorAll('button,input[type=button]'));
                for(var i=0;i<bs.length;i++){ if(/登录|提交|登 录|Login/.test(bs[i].value||bs[i].innerText||'')) { btn=bs[i]; break; } }
              }
              if(btn){ btn.click(); return 'clicked'; }
              var f=document.querySelector('form'); if(f){ f.submit(); return 'submitted'; }
              return 'nobutton';
            })()
        """.trimIndent()
        evalRaw(webView, js)
    }

    // ---------- JS 执行辅助 ----------

    private suspend fun evalRaw(webView: WebView, js: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(js) { cont.resume(it ?: "") }
        }

    private suspend fun evalJson(webView: WebView, js: String): Map<String, String> {
        val raw = evalRaw(webView, js).trim().trim('"')
        if (raw.isBlank() || raw == "null") return emptyMap()
        return try {
            val obj = org.json.JSONObject(raw.replace("\\\"", "\""))
            val map = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun evalString(webView: WebView, js: String): String {
        val raw = evalRaw(webView, js)
        return raw.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"")
    }

    /** 轮询等待条件满足 */
    private suspend fun poll(timeoutMs: Long, intervalMs: Long, check: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return true
            delay(intervalMs)
        }
        return false
    }

    private fun jsStr(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
