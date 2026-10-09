package com.pointblue.dirxml.dev.validate;

import com.pointblue.dirxml.dev.model.DriverSet;
import com.pointblue.dirxml.dev.model.NotificationTemplate;

/**
 * Notification templates ({@code notfMergeTemplate}, docs/console-gaps.md §12), on by default in
 * {@link Validator#standard}. Codes: {@code template-name-blank} (E), {@code template-no-data} (E),
 * {@code template-no-subject} (W).
 */
public final class TemplateCheck implements Check {
    @Override
    public String name() {
        return "templates";
    }

    @Override
    public void run(DriverSet ds, Report r) {
        for (NotificationTemplate t : ds.templates) {
            String path = "templates/" + t.name;
            if (t.name == null || t.name.isBlank()) {
                r.add(Finding.error("template-name-blank", path, "notification template has a blank name"));
            }
            if (t.data == null) {
                r.add(Finding.error("template-no-data", path, "notification template has no body document"));
            }
            if (t.subject == null || t.subject.isBlank()) {
                r.add(Finding.warning("template-no-subject", path, "notification template has no subject"));
            }
        }
    }
}
