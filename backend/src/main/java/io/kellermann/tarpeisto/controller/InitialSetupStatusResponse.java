package io.kellermann.tarpeisto.controller;

/** Public installation state used by the SPA to select setup or sign-in. */
public record InitialSetupStatusResponse(boolean setupRequired) {}
