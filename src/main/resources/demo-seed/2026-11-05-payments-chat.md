# Payments chat

[09:10] Priya Sharma: The Razorpay webhook retries can charge an order twice, so I am adding an idempotency key to payments.
[09:14] Priya Sharma: Decision: payments go only through Razorpay, we are dropping the Stripe prototype because the club has no international customers.
[09:20] Rahul Desai: ok
[14:02] Priya Sharma: The payments refund flow is done, refunds go back to the original UPI handle.
[14:30] Priya Sharma: I wrote the payments runbook in the drive.
