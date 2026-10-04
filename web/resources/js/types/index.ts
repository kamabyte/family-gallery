export interface Photo {
    id: number;
    type: 'photo' | 'video';
    filename: string;
    /** ISO 8601 со смещением — время съёмки по часам того места. */
    taken_at: string;
    /** YYYY-MM-DD: день, к которому относится кадр в ленте. */
    date: string;
    width: number;
    height: number;
    /** Длительность видео, секунды. */
    duration: number | null;
    favorite: boolean;
    color: string;
    thumb: string;
    preview: string;
    video: string | null;
    place: { city: string; country: string | null } | null;
    camera: string | null;
    gps: { lat: number; lon: number } | null;
}

export type AlbumType = 'trip' | 'place' | 'camera' | 'year' | 'smart';

export interface Album {
    id: string;
    type: AlbumType;
    name: string;
    subtitle: string | null;
    count: number;
    cover: Photo | null;
    from: string | null;
    to: string | null;
}

export interface Bucket {
    /** YYYY-MM */
    month: string;
    count: number;
}

export interface Memory {
    years_ago: number;
    date: string;
    count: number;
    cover: Photo;
}

export interface LibraryStatus {
    revision: number;
    photos: number;
    videos: number;
    indexed_at: string;
    size_bytes: number;
    /** Сгенерированная библиотека прототипа, а не каталог индексатора. */
    fake: boolean;
}

export type Role = 'admin' | 'member' | 'guest';

export interface User {
    id: number;
    name: string;
    email: string;
    role: Role;
    role_label: string;
}

export interface Auth {
    user: User;
    /** Что показывать; сами права проверяет сервер (гейты admin и family). */
    can: { admin: boolean; family: boolean };
}

export interface SharedProps {
    auth: Auth;
    sidebarPlaces: { id: string; name: string; count: number }[];
    library: LibraryStatus;
    [key: string]: unknown;
}

export interface Toast {
    type: 'success' | 'error';
    message: string;
    description?: string | null;
}

export type TwoFactorSetupData = {
    svg: string;
    url: string;
};

export type TwoFactorSecretKey = {
    secretKey: string;
};
