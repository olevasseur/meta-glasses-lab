import io
import errno
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import app
import photos
from tools import CLIENT_TOOL_NAMES, REGISTRY

WHEN = datetime.fromisoformat('2026-10-10T13:14:15-04:00')


def jpeg(size=(80, 40), color='red', fmt='JPEG', exif=None):
    output = io.BytesIO()
    Image.new('RGB', size, color).save(output, format=fmt, **({'exif': exif} if exif else {}))
    return output.getvalue()


@pytest.fixture
def store(tmp_path, monkeypatch):
    destination = tmp_path / 'Downloads'
    destination.mkdir()
    monkeypatch.setenv('PHOTO_SAVE_DIR', str(destination))
    service = photos.PhotoStore(tmp_path / 'data' / 'receipts.sqlite3')
    monkeypatch.setattr(photos, 'store', service)
    return service, destination


@pytest.fixture
def client(store, monkeypatch):
    monkeypatch.setenv('APP_SHARED_SECRET', 'test-photo-secret')
    with TestClient(app.app) as client:
        yield client


def post(client, raw=None, **kwargs):
    params = {'upload_id': str(uuid4()), 'captured_at': WHEN.isoformat(), **kwargs.pop('params', {})}
    return client.post('/photos', params=params, content=raw if raw is not None else jpeg(),
                       headers=kwargs.pop('headers', {'X-App-Secret': 'test-photo-secret', 'Content-Type': 'image/jpeg'}), **kwargs)


def test_save_full_resolution_orientation_metadata_and_mode(store):
    service, directory = store
    exif = Image.Exif()
    exif[274] = 6
    exif[270] = 'private caption'
    exif[34853] = {1: 'N', 2: (45.0, 0.0, 0.0)}
    result = service.save(jpeg((1800, 1200), exif=exif), uuid4(), WHEN, 'Beach sunset!')
    assert result['filename'] == 'glasses-2026-10-10_13-14-15-Beach-sunset.jpg'
    assert str(directory) not in str(result)
    path = directory / result['filename']
    assert path.stat().st_mode & 0o777 == 0o644
    with Image.open(path) as image:
        assert image.size == (1200, 1800)
        assert not image.getexif()
        assert not any(key in image.info for key in ('exif', 'xmp', 'icc_profile', 'comment'))
    assert list(directory.glob('.*')) == []


def test_collisions_and_restart_retry_are_append_only(store):
    service, directory = store
    photo_id = uuid4()
    first = service.save(jpeg(), photo_id, WHEN, None)
    second = service.save(jpeg(color='blue'), uuid4(), WHEN, None)
    assert second['filename'] == 'glasses-2026-10-10_13-14-15-2.jpg'
    reopened = photos.PhotoStore(service.receipts)
    assert reopened.save(jpeg(), photo_id, WHEN, None) == first
    assert len(list(directory.glob('*.jpg'))) == 2
    with pytest.raises(Exception) as error:
        reopened.save(jpeg(color='blue'), photo_id, WHEN, None)
    assert error.value.status_code == 409


def test_collision_with_existing_symlink_never_follows_or_overwrites(store, tmp_path):
    service, directory = store
    target = tmp_path / 'private'
    target.write_bytes(b'untouched')
    (directory / 'glasses-2026-10-10_13-14-15.jpg').symlink_to(target)
    result = service.save(jpeg(), uuid4(), WHEN, None)
    assert result['filename'].endswith('-2.jpg')
    assert target.read_bytes() == b'untouched'


def test_retry_after_publication_failure_uses_reserved_name(store, monkeypatch):
    service, directory = store
    photo_id = uuid4()
    link = os.link
    monkeypatch.setattr(os, 'link', lambda *a, **kw: (_ for _ in ()).throw(OSError('disk failed')))
    with pytest.raises(OSError):
        service.save(jpeg(), photo_id, WHEN, None)
    assert not list(directory.iterdir())
    monkeypatch.setattr(os, 'link', link)
    assert service.save(jpeg(), photo_id, WHEN, None)['filename'] == 'glasses-2026-10-10_13-14-15.jpg'


def test_concurrent_same_timestamp_never_overwrites(store):
    service, directory = store
    with ThreadPoolExecutor(4) as pool:
        names = list(pool.map(lambda _: service.save(jpeg(), uuid4(), WHEN, None)['filename'], range(4)))
    assert len(set(names)) == len(list(directory.glob('*.jpg'))) == 4


@pytest.mark.parametrize('label, suffix', [('../escape', '-escape'), ('/tmp/escape', '-tmp-escape'),
                                         ('a\\b', '-a-b'), ('x\x00y', '-x-y'), ('..', ''), ('', ''),
                                         ('x' * 81, '-' + 'x' * 60)])
def test_labels_are_sanitized_instead_of_blocking_capture(client, store, label, suffix):
    service, directory = store
    response = post(client, params={'label': label})
    assert response.status_code == 200
    assert response.json()['filename'] == f'glasses-2026-10-10_13-14-15{suffix}.jpg'
    assert len(list(directory.iterdir())) == 1


def test_destination_must_exist_and_default_is_backend_users_downloads(store, monkeypatch, tmp_path):
    service, _ = store
    missing = tmp_path / 'missing' / 'Downloads'
    monkeypatch.setenv('PHOTO_SAVE_DIR', str(missing))
    with pytest.raises(OSError):
        service.save(jpeg(), uuid4(), WHEN, None)
    assert not missing.exists()
    monkeypatch.delenv('PHOTO_SAVE_DIR')
    monkeypatch.setattr(photos.Path, 'home', classmethod(lambda cls: tmp_path))
    result = service.save(jpeg(), uuid4(), WHEN, None)
    assert result['message'].startswith("Saved to your Mac's Downloads as glasses-")


@pytest.mark.parametrize('raw', [b'', b'not jpeg', jpeg(fmt='PNG'), jpeg()[:100], b'x' * (photos.MAX_BYTES + 1)])
def test_invalid_jpeg(store, raw):
    service, directory = store
    with pytest.raises(ValueError):
        service.save(raw, uuid4(), WHEN, None)
    assert not list(directory.iterdir())


def test_pixel_limit_checked_before_decode(store, monkeypatch):
    service, _ = store
    # JPEG SOF dimensions: just over 24 MP, below the previous 50 MP cap.
    raw = bytearray(jpeg())
    index = raw.index(b'\xff\xc0')
    raw[index + 5:index + 9] = (4001).to_bytes(2, 'big') + (6000).to_bytes(2, 'big')
    monkeypatch.setattr(Image.Image, 'load', lambda *a, **kw: pytest.fail('decoded oversized image'))
    with pytest.raises(ValueError, match='pixel limit'):
        service.save(bytes(raw), uuid4(), WHEN, None)


def test_endpoint_auth_content_type_and_success(client):
    assert post(client, headers={}).status_code == 401
    assert post(client, headers={'X-App-Secret': 'test-photo-secret', 'Content-Type': 'image/png'}).status_code == 415
    response = post(client)
    assert response.status_code == 200
    assert response.headers['cache-control'] == 'no-store'
    assert response.json()['status'] == 'ok'
    assert set(response.json()) == {'status', 'filename', 'message'}


@pytest.mark.parametrize('size', [(4032, 3024), (6000, 4000)])
def test_dat_full_still_and_24mp_boundary_are_accepted(store, size):
    service, directory = store
    result = service.save(jpeg(size), uuid4(), WHEN, None)
    with Image.open(directory / result['filename']) as image:
        assert image.size == size


@pytest.mark.parametrize('kind', ['symlink', 'directory', 'fifo', 'regular', 'eloop_race', 'fifo_race'])
def test_reserved_nonregular_or_changed_destination_is_permanent(client, store, monkeypatch, tmp_path, kind):
    service, directory = store
    photo_id = str(uuid4())
    response = post(client, params={'upload_id': photo_id})
    destination = directory / response.json()['filename']
    destination.unlink()
    target = tmp_path / 'untouched'
    target.write_bytes(b'private')
    if kind == 'symlink':
        destination.symlink_to(target)
    elif kind == 'directory':
        destination.mkdir()
    elif kind == 'fifo':
        os.mkfifo(destination)
    else:
        destination.write_bytes(b'occupied')
    if kind.endswith('_race'):
        original_open = os.open

        def race(path, flags, *args, **kwargs):
            if path == destination:
                if kind == 'eloop_race':
                    raise OSError(errno.ELOOP, 'symlink swapped in')
                destination.unlink()
                os.mkfifo(destination)
            return original_open(path, flags, *args, **kwargs)

        monkeypatch.setattr(os, 'open', race)
    assert post(client, params={'upload_id': photo_id}).status_code == 409
    assert target.read_bytes() == b'private'
    assert not list(directory.glob('.glasses-*'))


@pytest.mark.parametrize('stage', ['resolve', 'mkstemp', 'link'])
def test_save_directory_permission_has_distinct_retryable_error(client, store, monkeypatch, stage):
    def denied(*args, **kwargs):
        raise PermissionError(errno.EACCES, 'TCC denied')

    with monkeypatch.context() as patch:
        if stage == 'resolve':
            patch.setattr(photos.Path, 'resolve', denied)
        elif stage == 'mkstemp':
            patch.setattr(photos.tempfile, 'mkstemp', denied)
        else:
            patch.setattr(os, 'link', denied)
        response = post(client)
    assert response.status_code == 507
    assert response.json() == {'detail': 'save_dir_permission'}
    assert not list(store[1].iterdir())


@pytest.mark.parametrize('params', [{'path': '/tmp/evil'}, {'upload_id': '../../bad'}, {'captured_at': '2026-10-10'}])
def test_endpoint_rejects_paths_and_invalid_metadata(client, params):
    assert post(client, params=params).status_code == 422


def test_body_limit_and_rate_limit(client):
    assert post(client, raw=b'x' * (photos.MAX_BYTES + 1)).status_code == 413
    for _ in range(5):
        assert post(client).status_code == 200
    assert post(client).status_code == 429


def test_client_owned_tool_never_executes_on_backend(client):
    assert 'save_photo' in CLIENT_TOOL_NAMES
    assert REGISTRY['save_photo'][1] == 'client'
    response = client.post('/tools/save_photo', json={}, headers={'X-App-Secret': 'test-photo-secret'})
    assert response.status_code == 400


def test_voice_simulator_returns_receipt_without_image_or_files(store):
    from voice_eval.simulator import AndroidTools
    result, image = AndroidTools({}).call('save_photo', {'label': 'sunset'})
    assert result['status'] == 'ok'
    assert image is None
    assert not list(store[1].iterdir())
