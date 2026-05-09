package com.apiconverter.controller;

import com.apiconverter.model.response.ResponseApiResponse;
import com.apiconverter.store.ResponseStore;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ResponseStore responseStore;

    @GetMapping("/api/responses")
    public Mono<List<ResponseApiResponse>> listResponses() {
        return Mono.just(responseStore.listResponses());
    }

    @GetMapping("/api/responses/{responseId}")
    public Mono<ResponseApiResponse> getResponse(@PathVariable String responseId) {
        return Mono.justOrEmpty(responseStore.get(responseId));
    }

    @GetMapping("/api/responses/{responseId}/conversation")
    public Mono<List<Map<String, Object>>> getConversation(@PathVariable String responseId) {
        return Mono.just(responseStore.getConversationMessages(responseId));
    }

    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> index() {
        return Mono.just(renderPage("responses", null, null));
    }

    @GetMapping(value = "/conversation/{responseId}", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> conversation(@PathVariable String responseId) {
        List<Map<String, Object>> messages = responseStore.getConversationMessages(responseId);
        return Mono.just(renderPage("conversation", responseId, messages));
    }

    private String renderPage(String view, String responseId, List<Map<String, Object>> messages) {
        if ("conversation".equals(view)) {
            return buildConversationPage(responseId, messages);
        }
        return buildResponsesPage();
    }

    private String buildResponsesPage() {
        return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>API Converter - Session Management</title>
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f5f5; color: #333; }
            .header { background: #1a1a2e; color: white; padding: 20px 32px; }
            .header h1 { font-size: 20px; font-weight: 600; }
            .header p { font-size: 13px; color: #aaa; margin-top: 4px; }
            .container { max-width: 1200px; margin: 24px auto; padding: 0 24px; }
            .card { background: white; border-radius: 8px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
            .empty { text-align: center; padding: 60px 20px; color: #999; }
            .empty p { margin-top: 8px; font-size: 14px; }
            #responses { list-style: none; }
            #responses li { padding: 16px 20px; border-bottom: 1px solid #eee; cursor: pointer; transition: background 0.15s; display: flex; justify-content: space-between; align-items: center; }
            #responses li:hover { background: #f8f9fa; }
            #responses li:last-child { border-bottom: none; }
            .resp-info { flex: 1; }
            .resp-id { font-family: monospace; font-size: 13px; color: #4f46e5; }
            .resp-meta { font-size: 12px; color: #999; margin-top: 4px; }
            .resp-meta span { margin-right: 16px; }
            .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 500; }
            .badge-completed { background: #dcfce7; color: #166534; }
            .badge-in-progress { background: #fef3c7; color: #92400e; }
            .actions { display: flex; gap: 8px; }
            .btn { display: inline-block; padding: 6px 12px; border-radius: 6px; font-size: 12px; text-decoration: none; border: 1px solid #ddd; color: #555; background: white; cursor: pointer; transition: all 0.15s; }
            .btn:hover { background: #f0f0f0; }
            .btn-primary { background: #4f46e5; color: white; border-color: #4f46e5; }
            .btn-primary:hover { background: #4338ca; }
            .refresh-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
            .refresh-bar h2 { font-size: 16px; font-weight: 600; }
        </style>
        </head>
        <body>
        <div class="header">
            <h1>API Converter</h1>
            <p>Session Management</p>
        </div>
        <div class="container">
            <div class="refresh-bar">
                <h2>Response Sessions</h2>
                <button class="btn btn-primary" onclick="loadResponses()">Refresh</button>
            </div>
            <div class="card">
                <ul id="responses">
                    <li class="empty" id="empty-state"><div><strong>No sessions yet</strong><p>Sessions will appear here after the first request with store=true</p></div></li>
                </ul>
            </div>
        </div>
        <script>
        function escapeHtml(text) {
            const d = document.createElement('div');
            d.textContent = text;
            return d.innerHTML;
        }
        function formatTime(ts) {
            if (!ts) return '-';
            return new Date(ts * 1000).toLocaleString();
        }
        function getPreview(resp) {
            if (!resp.output) return '';
            for (const item of resp.output) {
                if (item.type === 'message' && item.content) {
                    for (const c of item.content) {
                        if (c.text) return c.text.substring(0, 100) + (c.text.length > 100 ? '...' : '');
                    }
                }
            }
            return '';
        }
        function loadResponses() {
            fetch('/admin/api/responses').then(r => r.json()).then(data => {
                const ul = document.getElementById('responses');
                const empty = document.getElementById('empty-state');
                if (!data || data.length === 0) {
                    ul.innerHTML = '<li class="empty"><div><strong>No sessions yet</strong><p>Sessions will appear here after the first request with store=true</p></div></li>';
                    return;
                }
                ul.innerHTML = data.reverse().map(r => {
                    const preview = escapeHtml(getPreview(r));
                    const badge = r.status === 'completed' ? 'badge-completed' : 'badge-in-progress';
                    return '<li>' +
                        '<div class="resp-info">' +
                            '<div class="resp-id">' + escapeHtml(r.id) + '</div>' +
                            '<div class="resp-meta">' +
                                '<span>' + escapeHtml(r.model || '-') + '</span>' +
                                '<span>' + formatTime(r.created_at) + '</span>' +
                                '<span class="badge ' + badge + '">' + escapeHtml(r.status || 'unknown') + '</span>' +
                            '</div>' +
                            (preview ? '<div style="font-size:13px;color:#666;margin-top:6px;max-width:700px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + preview + '</div>' : '') +
                        '</div>' +
                        '<div class="actions">' +
                            '<a class="btn" href="/admin/conversation/' + r.id + '">View Conversation</a>' +
                        '</div>' +
                    '</li>';
                }).join('');
            }).catch(err => console.error('Failed to load responses:', err));
        }
        loadResponses();
        </script>
        </body>
        </html>
        """;
    }

    private String buildConversationPage(String responseId, List<Map<String, Object>> messages) {
        StringBuilder msgHtml = new StringBuilder();
        if (messages != null) {
            for (Map<String, Object> msg : messages) {
                String role = String.valueOf(msg.getOrDefault("role", ""));
                String content = String.valueOf(msg.getOrDefault("content", ""));
                boolean isUser = "user".equals(role);
                boolean isAssistant = "assistant".equals(role);
                boolean isSystem = "system".equals(role);
                String cssClass = isAssistant ? "msg-assistant" : (isSystem ? "msg-system" : "msg-user");
                String roleLabel = isAssistant ? "Assistant" : (isSystem ? "System" : "User");
                msgHtml.append("<div class=\"msg ").append(cssClass).append("\">")
                        .append("<div class=\"msg-role\">").append(roleLabel).append("</div>")
                        .append("<div class=\"msg-content\">").append(escapeHtml(content)).append("</div>")
                        .append("</div>");
            }
        }

        return """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Conversation - """ + escapeHtml(responseId) + """
        </title>
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f5f5; color: #333; }
            .header { background: #1a1a2e; color: white; padding: 20px 32px; display: flex; justify-content: space-between; align-items: center; }
            .header h1 { font-size: 18px; font-weight: 600; }
            .header .resp-id { font-family: monospace; font-size: 12px; color: #aaa; margin-top: 4px; }
            .btn { display: inline-block; padding: 8px 16px; border-radius: 6px; font-size: 13px; text-decoration: none; border: 1px solid rgba(255,255,255,0.3); color: white; background: transparent; cursor: pointer; transition: all 0.15s; }
            .btn:hover { background: rgba(255,255,255,0.1); }
            .container { max-width: 800px; margin: 24px auto; padding: 0 24px; }
            .msg { padding: 16px 20px; border-radius: 12px; margin-bottom: 12px; max-width: 85%; }
            .msg-user { background: #4f46e5; color: white; margin-left: auto; border-bottom-right-radius: 4px; }
            .msg-assistant { background: white; border-bottom-left-radius: 4px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
            .msg-system { background: #fef3c7; border-bottom-left-radius: 4px; }
            .msg-role { font-size: 11px; font-weight: 600; text-transform: uppercase; margin-bottom: 6px; opacity: 0.7; }
            .msg-content { font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-wrap: break-word; }
            .empty { text-align: center; padding: 60px 20px; color: #999; }
        </style>
        </head>
        <body>
        <div class="header">
            <div>
                <h1>Conversation</h1>
                <div class="resp-id">""" + escapeHtml(responseId) + """
                </div>
            </div>
            <a class="btn" href="/admin/">Back to List</a>
        </div>
        <div class="container">
        """ + (msgHtml.length() > 0 ? msgHtml.toString() : "<div class=\"empty\"><strong>No messages found</strong></div>") + """
        </div>
        </body>
        </html>
        """;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
