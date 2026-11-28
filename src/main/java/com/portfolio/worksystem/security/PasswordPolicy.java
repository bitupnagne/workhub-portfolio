package com.portfolio.worksystem.security;

/**
 * 비밀번호 복잡도 정책(P0 보안). 영문 + 숫자 + 특수문자를 모두 포함한 9자 이상.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 9;

    private PasswordPolicy() {}

    /** 정책 위반 시 IllegalArgumentException. */
    public static void validate(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다.");
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        boolean special = password.chars().anyMatch(
            c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
        if (!(letter && digit && special)) {
            throw new IllegalArgumentException("비밀번호는 영문·숫자·특수문자를 모두 포함해야 합니다.");
        }
    }
}
