/**
 * Request DTOs validate shape through Jakarta Bean Validation. Dedicated services here
 * normalize inputs and enforce reusable business rules. Database-dependent guards run
 * inside their caller's transaction after the relevant calendar locks are acquired.
 */
package com.src.minidoodle.service.system.validation;
