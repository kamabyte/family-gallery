const LOCALE = 'ru-RU';

const MONTHS = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];

/** Дата YYYY-MM-DD как локальная полночь, без сдвига часовым поясом браузера. */
function parseDay(date: string) {
    const [y, m, d] = date.split('-').map(Number);
    return new Date(y, m - 1, d);
}

function todayKey(offsetDays = 0) {
    const now = new Date();
    now.setDate(now.getDate() + offsetDays);
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

/** «Октябрь 2026» */
export function monthTitle(month: string) {
    const [y, m] = month.split('-').map(Number);
    return `${MONTHS[m - 1]} ${y}`;
}

export function monthShort(month: string) {
    const [, m] = month.split('-').map(Number);
    return MONTHS[m - 1].slice(0, 3);
}

/** «Сегодня», «Вчера», «Пт, 12 сентября» или «Пт, 12 сентября 2023». */
export function dayTitle(date: string) {
    if (date === todayKey()) return 'Сегодня';
    if (date === todayKey(-1)) return 'Вчера';

    const day = parseDay(date);
    const sameYear = day.getFullYear() === new Date().getFullYear();
    const text = day.toLocaleDateString(LOCALE, {
        weekday: 'short',
        day: 'numeric',
        month: 'long',
        ...(sameYear ? {} : { year: 'numeric' }),
    });

    return text.charAt(0).toUpperCase() + text.slice(1).replace(' г.', '');
}

/** «суббота, 4 октября 2026 г., 09:30» — в зоне, где снимали. */
export function fullDateTime(iso: string) {
    const date = new Date(iso);
    const text = date.toLocaleString(LOCALE, {
        weekday: 'long',
        day: 'numeric',
        month: 'long',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        timeZone: zoneOf(iso),
    });

    return text.charAt(0).toUpperCase() + text.slice(1);
}

export function timeOnly(iso: string) {
    return new Date(iso).toLocaleTimeString(LOCALE, { hour: '2-digit', minute: '2-digit', timeZone: zoneOf(iso) });
}

/** Смещение из ISO-строки как зона «Etc/GMT±N» — Intl понимает только целые часы. */
function zoneOf(iso: string) {
    const match = iso.match(/([+-])(\d{2}):(\d{2})$/);
    if (!match || match[3] !== '00') return undefined;
    const hours = Number(match[2]);
    if (hours === 0) return 'UTC';
    // У Etc/GMT знак наоборот: Etc/GMT-3 — это UTC+3.
    return `Etc/GMT${match[1] === '+' ? '-' : '+'}${hours}`;
}

export function duration(seconds: number) {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
}

export function count(n: number, forms: [string, string, string]) {
    const mod10 = n % 10;
    const mod100 = n % 100;
    const form = mod10 === 1 && mod100 !== 11 ? forms[0] : mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20) ? forms[1] : forms[2];
    return `${n.toLocaleString(LOCALE)} ${form}`;
}

export const items = (n: number) => count(n, ['фото', 'фото', 'фото']);
export const videos = (n: number) => count(n, ['видео', 'видео', 'видео']);
export const yearsAgo = (n: number) => (n === 1 ? 'Год назад' : count(n, ['год', 'года', 'лет']) + ' назад');

export function bytes(n: number) {
    const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
    let value = n;
    let unit = 0;
    while (value >= 1000 && unit < units.length - 1) {
        value /= 1000;
        unit += 1;
    }
    return `${value.toLocaleString(LOCALE, { maximumFractionDigits: value < 10 ? 1 : 0 })} ${units[unit]}`;
}

/** «2019–2026» или «2024» */
export function yearRange(from: string | null, to: string | null) {
    if (!from || !to) return null;
    const a = from.slice(0, 4);
    const b = to.slice(0, 4);
    return a === b ? a : `${a}–${b}`;
}

export function megapixels(width: number, height: number) {
    return `${((width * height) / 1_000_000).toLocaleString(LOCALE, { maximumFractionDigits: 1 })} Мп`;
}
