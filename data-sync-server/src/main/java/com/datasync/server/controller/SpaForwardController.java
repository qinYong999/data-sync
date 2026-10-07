package com.datasync.server.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 单页应用（SPA）前端路由转发。
 *
 * <h2>为什么需要这个类（实测发现的部署缺口）</h2>
 * 前端是 Vue 3 + vue-router 的 history 模式，构建产物放进 {@code classpath:/static/}
 * （Dockerfile 会把 {@code data-sync-web/dist/} 拷进去），目标是"同一个 JAR 同时提供页面与 /api"。
 *
 * <p>但后端原先**没有任何转发规则**：前端资源虽然被打进了包里，却没有任何东西把
 * {@code /}、{@code /tasks}、{@code /login} 这些前端路由指到 {@code index.html}，
 * 于是访问根路径是 404 —— 也就是 Docker 部署出来的界面打不开。
 * 这类缺口在"只跑 API 冒烟测试"时完全看不出来，因为所有 API 都正常。</p>
 *
 * <h2>转发规则（只转发"看起来像前端路由"的路径）</h2>
 * <ul>
 *   <li><b>转发</b>：{@code /}、{@code /tasks}、{@code /records/1} 这类不含 {@code .} 的路径
 *       —— history 模式下服务端没有对应文件，必须交回 index.html 由前端路由接管。</li>
 *   <li><b>不转发</b>：含 {@code .} 的路径（{@code /assets/index-xxx.js}、{@code /favicon.ico} 等）
 *       —— 它们该由 Spring 静态资源处理直接命中。若在这里吞掉，静态资源 404 也会返回一份 HTML，
 *       浏览器报 "Unexpected token '&lt;'"，极难排查。</li>
 *   <li><b>不转发</b>：{@code /api/**}、{@code /ws/**}、{@code /actuator/**} 等保留前缀
 *       —— 让它们照常走到自己的处理器（或照常 404），不要把接口路径伪装成页面。</li>
 * </ul>
 *
 * <h2>前端资源不存在时怎么办</h2>
 * 若 {@code static/index.html} 不存在（例如只构建了后端、没构建前端），
 * 这里返回一段**明确的中文说明**而不是空 404 —— 这是"部署不完整"而不是"路径写错"，
 * 两者排查方向完全不同，错误信息应当直接说清。
 */
@Controller
public class SpaForwardController {

    private static final Logger log = LoggerFactory.getLogger(SpaForwardController.class);

    private static final String INDEX_LOCATION = "static/index.html";

    /**
     * 匹配"不含点号、且不是保留前缀"的路径。
     * <p>注意 {@code {path:^(?!api|ws|actuator|assets)[^\.]*}} 这个负向前瞻是刻意的：
     * Spring 的路径模式在同一段里只允许一个正则，所以把两个条件合并写。</p>
     */
    @GetMapping({"/", "/{path:^(?!api$|ws$|actuator$|assets$)[^\\.]*}", "/{path:^(?!api$|ws$|actuator$|assets$)[^\\.]*}/**"})
    @ResponseBody
    public ResponseEntity<String> forwardSpaRoutes() throws IOException {
        Resource index = new ClassPathResource(INDEX_LOCATION);
        if (!index.exists()) {
            return notBuilt();
        }
        String html = new String(index.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                // 入口 HTML 不能缓存，否则前端发版后用户仍加载旧的资源引用
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .body(html);
    }

    private ResponseEntity<String> notBuilt() {
        log.warn("前端资源 {} 不存在：无法返回页面。"
                + "请先构建前端（npm run build）并把 dist 内容放到 "
                + "data-sync-server/src/main/resources/static/，或用 Dockerfile 构建（它会自动完成这一步）。",
                INDEX_LOCATION);
        String body = """
                <!DOCTYPE html>
                <html lang="zh-CN"><head><meta charset="UTF-8"><title>DataSync 前端资源未构建</title></head>
                <body style="font-family:system-ui,sans-serif;max-width:720px;margin:64px auto;line-height:1.7">
                  <h1>前端资源未构建</h1>
                  <p>后端已经正常启动，但运行包里没有前端页面（<code>static/index.html</code> 不存在）。</p>
                  <p>这不是路径写错了，而是<strong>部署不完整</strong>。两种解决办法：</p>
                  <ol>
                    <li><strong>用 Dockerfile 构建</strong>（推荐）——它会自动构建前端并放进包里。</li>
                    <li><strong>手动构建</strong>：<code>cd data-sync-web &amp;&amp; npm install &amp;&amp; npm run build</code>，
                        然后把 <code>dist/</code> 里的内容复制到
                        <code>data-sync-server/src/main/resources/static/</code>，再重新打包。</li>
                  </ol>
                  <p>接口不受影响，例如 <a href="/actuator/health">/actuator/health</a> 仍可访问。</p>
                </body></html>
                """;
        return ResponseEntity.status(503)
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }
}
