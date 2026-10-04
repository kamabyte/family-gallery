import { Form, Head, router, usePage } from '@inertiajs/react';
import { Heart, KeyRound, MoreHorizontal, Trash2, UserPlus } from 'lucide-react';
import { useEffect, useState } from 'react';
import UserController from '@/actions/App/Http/Controllers/UserController';
import { useConfirm } from '@/components/confirm';
import { Field } from '@/components/field';
import Heading from '@/components/heading';
import InputError from '@/components/input-error';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from '@/components/ui/dialog';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import {
    Select,
    SelectContent,
    SelectItem,
    SelectTrigger,
    SelectValue,
} from '@/components/ui/select';
import { useClipboard } from '@/hooks/use-clipboard';
import type { Role } from '@/types';

type Row = {
    id: number;
    name: string;
    email: string;
    role: Role;
    two_factor: boolean;
    favorites: number;
    created_at: string | null;
    is_me: boolean;
};

type Props = {
    users: Row[];
    roles: { value: Role; label: string; description: string }[];
};

type NewPassword = { email: string; password: string };

export default function Users({ users, roles }: Props) {
    const [confirm, confirmDialog] = useConfirm();
    const [created, setCreated] = useState<NewPassword | null>(null);
    const { errors } = usePage().props as { errors: Record<string, string> };
    const label = (r: Role) => roles.find((x) => x.value === r)?.label ?? r;

    // сгенерированный пароль приходит флешем и показывается один раз
    useEffect(
        () =>
            router.on('flash', (event) => {
                const p = (event as CustomEvent).detail?.flash?.password as
                    | NewPassword
                    | undefined;

                if (p) {
                    setCreated(p);
                }
            }),
        [],
    );

    const setRole = (u: Row, role: Role) =>
        router.patch(
            UserController.update.url(u.id),
            { role },
            { preserveScroll: true },
        );

    return (
        <>
            <Head title="Пользователи" />
            <div className="space-y-6">
                <div className="flex flex-wrap items-start justify-between gap-4">
                    <Heading
                        variant="small"
                        title="Пользователи"
                        description="Кто может смотреть галерею и что ему можно"
                    />
                    <CreateUserDialog roles={roles} />
                </div>

                {created && (
                    <NewPasswordAlert
                        value={created}
                        onClose={() => setCreated(null)}
                    />
                )}
                <InputError
                    message={errors.user ?? errors.role}
                />

                <div className="divide-y rounded-lg border">
                    {users.map((u) => (
                        <div
                            key={u.id}
                            className="flex items-center gap-3 px-4 py-3"
                        >
                            <div className="grid min-w-0 flex-1 gap-0.5">
                                <div className="flex items-center gap-2 truncate font-medium">
                                    {u.name}
                                    {u.is_me && (
                                        <span className="text-xs font-normal text-muted-foreground">
                                            это вы
                                        </span>
                                    )}
                                </div>
                                <div className="truncate text-sm text-muted-foreground">
                                    {u.email}
                                    {u.two_factor && ' · 2FA'}
                                </div>
                            </div>
                            {u.role !== 'guest' && (
                                <span
                                    className="hidden items-center gap-1 text-sm text-muted-foreground tabular-nums sm:inline-flex"
                                    title="В избранном"
                                >
                                    <Heart className="size-3.5" />
                                    {u.favorites}
                                </span>
                            )}
                            {u.is_me ? (
                                <Badge
                                    variant={
                                        u.role === 'admin'
                                            ? 'default'
                                            : 'secondary'
                                    }
                                >
                                    {label(u.role)}
                                </Badge>
                            ) : (
                                <Select
                                    value={u.role}
                                    onValueChange={(v) => setRole(u, v as Role)}
                                >
                                    <SelectTrigger className="w-44">
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {roles.map((r) => (
                                            <SelectItem
                                                key={r.value}
                                                value={r.value}
                                            >
                                                {r.label}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            )}
                            <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                    <Button
                                        variant="ghost"
                                        size="icon"
                                        aria-label="Действия"
                                    >
                                        <MoreHorizontal />
                                    </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end">
                                    <DropdownMenuItem
                                        onClick={() =>
                                            confirm({
                                                title: 'Сбросить пароль?',
                                                description: `${u.email} получит новый пароль, старый перестанет действовать, открытые сессии закроются.`,
                                                action: 'Сбросить',
                                                onConfirm: () =>
                                                    router.patch(
                                                        UserController.update.url(
                                                            u.id,
                                                        ),
                                                        {
                                                            reset_password: true,
                                                        },
                                                        {
                                                            preserveScroll: true,
                                                        },
                                                    ),
                                            })
                                        }
                                    >
                                        <KeyRound /> Сбросить пароль
                                    </DropdownMenuItem>
                                    {!u.is_me && (
                                        <>
                                            <DropdownMenuSeparator />
                                            <DropdownMenuItem
                                                variant="destructive"
                                                onClick={() =>
                                                    confirm({
                                                        title: 'Удалить учётную запись?',
                                                        description: `${u.email} больше не сможет войти; его избранное удалится. Сами фотографии не пострадают.`,
                                                        action: 'Удалить',
                                                        destructive: true,
                                                        onConfirm: () =>
                                                            router.delete(
                                                                UserController.destroy.url(
                                                                    u.id,
                                                                ),
                                                                {
                                                                    preserveScroll: true,
                                                                },
                                                            ),
                                                    })
                                                }
                                            >
                                                <Trash2 /> Удалить
                                            </DropdownMenuItem>
                                        </>
                                    )}
                                </DropdownMenuContent>
                            </DropdownMenu>
                        </div>
                    ))}
                </div>

                <div className="grid gap-1 text-sm text-muted-foreground">
                    {roles.map((r) => (
                        <p key={r.value}>
                            <span className="font-medium text-foreground">
                                {r.label}
                            </span>{' '}
                            — {r.description.toLowerCase()}
                        </p>
                    ))}
                </div>
            </div>
            {confirmDialog}
        </>
    );
}

function NewPasswordAlert({
    value,
    onClose,
}: {
    value: NewPassword;
    onClose: () => void;
}) {
    const [copiedText, copy] = useClipboard();

    return (
        <Alert>
            <KeyRound />
            <AlertTitle>Пароль для {value.email}</AlertTitle>
            <AlertDescription className="grid gap-2">
                <p>
                    Показывается один раз — сохраните или сразу передайте
                    владельцу.
                </p>
                <div className="flex flex-wrap items-center gap-2">
                    <code className="rounded bg-muted px-2 py-1 font-mono text-sm text-foreground">
                        {value.password}
                    </code>
                    <Button
                        size="sm"
                        variant="outline"
                        onClick={() => copy(value.password)}
                    >
                        {copiedText === value.password
                            ? 'Скопировано'
                            : 'Копировать'}
                    </Button>
                    <Button size="sm" variant="ghost" onClick={onClose}>
                        Скрыть
                    </Button>
                </div>
            </AlertDescription>
        </Alert>
    );
}

function CreateUserDialog({ roles }: Pick<Props, 'roles'>) {
    const [open, setOpen] = useState(false);
    const [role, setRole] = useState<Role>('member');

    return (
        <Dialog open={open} onOpenChange={setOpen}>
            <DialogTrigger asChild>
                <Button>
                    <UserPlus /> Добавить
                </Button>
            </DialogTrigger>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Новая учётная запись</DialogTitle>
                    <DialogDescription>
                        Регистрации нет — учётные записи заводит администратор.
                    </DialogDescription>
                </DialogHeader>
                <Form
                    {...UserController.store.form()}
                    options={{ preserveScroll: true }}
                    onSuccess={() => {
                        setOpen(false);
                        setRole('member');
                    }}
                    className="grid gap-4"
                >
                    {({ errors, processing }) => (
                        <>
                            <Field
                                label="Email"
                                htmlFor="user_email"
                                error={errors.email}
                            >
                                <Input
                                    id="user_email"
                                    name="email"
                                    type="email"
                                    required
                                    autoComplete="off"
                                />
                            </Field>
                            <Field
                                label="Имя"
                                htmlFor="user_name"
                                hint="Пусто — часть email до @"
                                error={errors.name}
                            >
                                <Input
                                    id="user_name"
                                    name="name"
                                    autoComplete="off"
                                />
                            </Field>
                            <Field label="Роль" error={errors.role}>
                                <Select
                                    name="role"
                                    value={role}
                                    onValueChange={(v) => setRole(v as Role)}
                                >
                                    <SelectTrigger>
                                        <SelectValue />
                                    </SelectTrigger>
                                    <SelectContent>
                                        {roles.map((r) => (
                                            <SelectItem
                                                key={r.value}
                                                value={r.value}
                                            >
                                                {r.label} — {r.description.toLowerCase()}
                                            </SelectItem>
                                        ))}
                                    </SelectContent>
                                </Select>
                            </Field>
                            <Field
                                label="Пароль"
                                htmlFor="user_password"
                                hint="Пусто — сгенерировать и показать один раз"
                                error={errors.password}
                            >
                                <Input
                                    id="user_password"
                                    name="password"
                                    type="password"
                                    autoComplete="new-password"
                                />
                            </Field>
                            <DialogFooter>
                                <Button type="submit" disabled={processing}>
                                    Завести
                                </Button>
                            </DialogFooter>
                        </>
                    )}
                </Form>
            </DialogContent>
        </Dialog>
    );
}
