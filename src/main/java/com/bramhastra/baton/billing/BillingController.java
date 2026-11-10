package com.bramhastra.baton.billing;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bramhastra.baton.admin.AdminService;
import com.bramhastra.baton.security.CurrentUser;
import com.bramhastra.baton.security.CurrentUserProvider;

@RestController
@RequestMapping("/api")
public class BillingController {

    private final BillingService billing;
    private final CurrentUserProvider currentUser;

    public BillingController(BillingService billing, CurrentUserProvider currentUser) {
        this.billing = billing;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/billing")
    public BillingService.Overview get() {
        AdminService.requireAdmin(currentUser.get());
        return billing.overview();
    }

    /** Returns the payment page to send the admin to. */
    @PostMapping("/admin/billing/checkout")
    public Map<String, String> checkout() {
        CurrentUser me = currentUser.get();
        AdminService.requireAdmin(me);
        return Map.of("url", billing.checkout(me));
    }

    /** Called by Razorpay, not by a browser: authenticated by its signature, not a session. */
    @PostMapping("/billing/razorpay/webhook")
    public Map<String, Boolean> webhook(@RequestBody String body,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        billing.webhook(body, signature);
        return Map.of("ok", true);
    }
}
