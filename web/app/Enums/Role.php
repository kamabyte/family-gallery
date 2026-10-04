<?php

namespace App\Enums;

/**
 * Роль учётной записи галереи.
 *
 * admin  — всё, плюс учётные записи;
 * member — семья: смотреть, отмечать избранное, скачивать;
 * guest  — только смотреть: ни избранного, ни скачивания.
 */
enum Role: string
{
    case Admin = 'admin';
    case Member = 'member';
    case Guest = 'guest';

    public function label(): string
    {
        return match ($this) {
            self::Admin => 'Администратор',
            self::Member => 'Семья',
            self::Guest => 'Гость',
        };
    }

    public function description(): string
    {
        return match ($this) {
            self::Admin => 'Всё, включая учётные записи',
            self::Member => 'Смотреть, избранное, скачивание',
            self::Guest => 'Только смотреть',
        };
    }
}
