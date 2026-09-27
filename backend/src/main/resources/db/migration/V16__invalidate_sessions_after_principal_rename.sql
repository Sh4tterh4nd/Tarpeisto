-- Serialized SecurityContext values contain the former principal class name.
-- Delete parent sessions; SPRING_SESSION_ATTRIBUTES follows through its existing FK cascade.
DELETE FROM SPRING_SESSION;
