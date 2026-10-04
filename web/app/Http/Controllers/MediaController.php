<?php

namespace App\Http\Controllers;

use App\Gallery\Catalog\IndexerCatalog;
use App\Gallery\GalleryCatalog;
use Symfony\Component\HttpFoundation\BinaryFileResponse;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpFoundation\ResponseHeaderBag;

/**
 * Files from the library, after the login check (routes: auth middleware).
 *
 * Derivatives are reachable only by their exact content-addressed shape
 * (.gallery/thumbs/<xx>/<sha256>_t|p.webp, _v.mp4) — never an arbitrary path, so
 * nothing outside the thumbnails is served this way. Their name changes with their
 * content, so the browser may cache them forever. Originals go through download(),
 * by photo id, and only to those allowed to download (gate family).
 */
class MediaController extends Controller
{
    private const DERIVATIVE = '#^\.gallery/thumbs/[0-9a-f]{2}/[0-9a-f]{64}_(t\.webp|p\.webp|v\.mp4)$#';

    public function show(string $path): Response
    {
        abort_unless(preg_match(self::DERIVATIVE, $path) === 1, 404);

        return $this->send($path, [
            'Content-Type' => str_ends_with($path, '.mp4') ? 'video/mp4' : 'image/webp',
            'Cache-Control' => 'private, max-age=31536000, immutable',
        ]);
    }

    public function download(GalleryCatalog $catalog, int $photo): Response
    {
        $item = $catalog->photo($photo) ?? abort(404);
        // The fake library has no files behind it.
        abort_unless($catalog instanceof IndexerCatalog, 404);
        $path = $catalog->originalPath($item->id) ?? abort(404);

        return $this->send($path, [
            'Content-Type' => 'application/octet-stream',
            'Content-Disposition' => self::disposition($item->filename),
            'Cache-Control' => 'private, no-store',
        ]);
    }

    /** attachment; filename="IMG_0001.HEIC"; filename*=UTF-8''… — safe for any name. */
    private static function disposition(string $filename): string
    {
        $fallback = preg_replace('/[^\x20-\x7e]|["\\\\%\/]/', '_', $filename) ?: 'photo';

        // Symfony refuses / and \ even in the UTF-8 name.
        $filename = strtr($filename, ['/' => '_', '\\' => '_']);

        return (new ResponseHeaderBag)->makeDisposition(ResponseHeaderBag::DISPOSITION_ATTACHMENT, $filename, $fallback);
    }

    /** @param  array<string, string>  $headers */
    private function send(string $path, array $headers): Response
    {
        $file = rtrim((string) config('gallery.root'), '/').'/'.$path;
        abort_unless(is_file($file), 404);

        // Behind nginx: it sends the file itself (sendfile, Range for video).
        if ($prefix = config('gallery.accel_redirect')) {
            return response('', 200, [...$headers, 'X-Accel-Redirect' => $prefix.'/'.implode('/', array_map('rawurlencode', explode('/', $path)))]);
        }

        // public: false — Symfony marks file responses public by default, which would let a
        // shared cache keep family photos; ours are per-login.
        $response = new BinaryFileResponse($file, 200, $headers, public: false);
        if (isset($headers['Content-Disposition'])) {
            $response->headers->set('Content-Disposition', $headers['Content-Disposition']);
        }

        return $response;
    }
}
