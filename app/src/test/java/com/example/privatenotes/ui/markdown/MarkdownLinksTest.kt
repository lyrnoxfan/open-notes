package com.example.privatenotes.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinksTest {
    @Test fun `ordinary external links remain usable`() {
        listOf("https://example.com/page?q=1", "HTTP://example.com", "mailto:hello@example.com", "tel:+123456789").forEach {
            assertTrue(it, MarkdownLinks.isAllowed(it))
        }
    }

    @Test fun `unsafe and malformed destinations never leave the app`() {
        listOf("javascript:alert(1)", "file:///data/data/private", "content://private/notes", "intent://scan/#Intent;scheme=zxing;end", "data:text/html,test", "https:", "https://", "https://user:pass@example.com", "https://example.com\n", "relative.md", "//example.com", "tel:").forEach {
            assertFalse(it, MarkdownLinks.isAllowed(it))
        }
    }
}
