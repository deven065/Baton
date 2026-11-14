# Pull request #41: Move order events to a message queue
Author: Arjun Mehta
Date: 2026-10-12

We keep losing orders when the API restarts, so the warehouse never sees them.

— Priya Sharma (2026-10-12T10:00:00Z): Should we use RabbitMQ for the order stream?
— Arjun Mehta (2026-10-12T10:20:00Z): We chose Kafka over RabbitMQ because we need message replay and ordering per key.
— Neha Kulkarni (2026-10-12T11:00:00Z): Approved. Arjun owns the consumer.
