import { useState } from 'react';
import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from '@/components/ui/alert-dialog';

export type ConfirmRequest = {
    title: string;
    description: React.ReactNode;
    action: string;
    destructive?: boolean;
    onConfirm: () => void;
};

/** Одно окно подтверждения на страницу: const [confirm, dialog] = useConfirm(). */
export function useConfirm(): [(r: ConfirmRequest) => void, React.ReactNode] {
    const [req, setReq] = useState<ConfirmRequest | null>(null);

    const dialog = (
        <AlertDialog
            open={req !== null}
            onOpenChange={(o) => !o && setReq(null)}
        >
            <AlertDialogContent>
                <AlertDialogHeader>
                    <AlertDialogTitle>{req?.title}</AlertDialogTitle>
                    <AlertDialogDescription asChild>
                        <div>{req?.description}</div>
                    </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                    <AlertDialogCancel>Отмена</AlertDialogCancel>
                    <AlertDialogAction
                        className={
                            req?.destructive
                                ? 'bg-destructive text-white hover:bg-destructive/90'
                                : ''
                        }
                        onClick={() => {
                            req?.onConfirm();
                            setReq(null);
                        }}
                    >
                        {req?.action}
                    </AlertDialogAction>
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );

    return [setReq, dialog];
}
