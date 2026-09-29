package dev.lingoflow.shared.core.project

/**
 * The bundled demo project.
 *
 * Kept in the pure Kotlin core so it is covered by unit tests: if a catalog or
 * the config ever stops parsing, the test suite fails before shipping.
 */
object DemoProjectData {
    const val NAME: String = "Demo project"

    val files: Map<String, String> = linkedMapOf(

        "lingoflow.config.json" to """
            {
              "sourceLocale": "en",
              "locales": ["en", "zh-CN", "ja", "de", "en-XA"],
              "catalogs": [{ "path": "locales/{locale}.json", "format": "auto" }],
              "translation": { "engine": "offline", "policy": "missing", "memory": { "fuzzyAutofill": true, "learnFromEdits": true } },
              "length": {
                "enabled": true,
                "unit": "both",
                "font": { "size": 14, "weight": 400 },
                "default": { "max": 60, "min": 0, "hard": false, "widget": "label" },
                "rules": [
                  { "match": "cta.*", "max": 18, "hard": true, "widget": "button" },
                  { "match": "nav.*", "max": 24, "widget": "nav" },
                  { "match": "*.tooltip", "max": 120, "widget": "tooltip" }
                ],
                "expansion": { "zh-CN": 0.7, "ja": 0.8, "de": 1.4 }
              },
              "validate": { "cjk": true, "icu": true, "placeholders": true, "consistency": true }
            }
        """.trimIndent() + "\n",

        "locales/en.json" to """
            {
              "app": { "title": "LingoFlow", "subtitle": "Ship 12 languages without breaking the layout" },
              "cta": { "save": "Save changes", "cancel": "Cancel", "delete": "Delete account" },
              "nav": { "settings": "Settings", "profile": "Profile", "billing": "Billing" },
              "form": { "email": "Email address", "required": "This field is required" },
              "items": { "count": "{count, plural, one {# item} other {# items}}" },
              "dialog": { "deleteTitle": "Delete {name}?", "deleteBody": "This action cannot be undone. All data for {name} will be permanently removed." },
              "message": { "welcome": "Welcome back, {name}!" },
              "legal": { "terms": "By continuing you agree to our <a>Terms of Service</a>." }
            }
        """.trimIndent() + "\n",

        "locales/zh-CN.json" to """
            {
              "app": { "title": "LingoFlow 演示", "subtitle": "一次发布 12 种语言，布局不错位" },
              "cta": { "save": "保存更改", "cancel": "取消" },
              "nav": { "settings": "设置" },
              "form": { "email": "邮箱地址", "required": "" },
              "items": { "count": "{数量, plural, other {# 项}}" },
              "message": { "welcome": "Welcome back, {name}!" }
            }
        """.trimIndent() + "\n",

        "locales/ja.json" to """
            {
              "app": { "title": "LingoFlow デモ" },
              "cta": { "save": "変更を保存", "cancel": "キャンセル" },
              "nav": { "settings": "設定" },
              "form": { "email": "メールアドレス" }
            }
        """.trimIndent() + "\n",

        "locales/de.json" to """
            {
              "app": { "title": "LingoFlow Demo" },
              "cta": { "save": "Änderungen speichern", "cancel": "Abbrechen" },
              "nav": { "settings": "Einstellungen" },
              "form": { "email": "E-Mail-Adresse" }
            }
        """.trimIndent() + "\n",

        "lingoflow.glossary.json" to """
            { "terms": [
              { "source": "LingoFlow", "target": "LingoFlow", "locale": null, "note": "brand name" },
              { "source": "Settings", "target": "设置", "locale": "zh-CN" }
            ] }
        """.trimIndent() + "\n",

        "lingoflow.style.json" to """
            { "rules": [
              { "name": "punctuation.trailing", "value": "never", "locale": "zh-CN" },
              { "name": "cjk.fullwidth", "value": "true", "locale": "zh-CN" },
              { "name": "cjk.latin-space", "value": "true", "locale": "zh-CN" },
              { "name": "formality", "value": "formal", "locale": "zh-CN" }
            ] }
        """.trimIndent() + "\n",

    )
}
