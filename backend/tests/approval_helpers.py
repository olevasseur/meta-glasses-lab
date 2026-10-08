"""Exercise the actual browser authorization flow in existing integration tests."""
import os
import re

import dashboard


def field(response, name):
    return re.search(fr'name="{name}" value="([^"]+)"', response.text)[1]


def browser_login(client):
    session = dashboard.sessions.get(client.cookies.get(dashboard.COOKIE))
    if session and session['authenticated']:
        return
    page = client.get('/dashboard/login')
    result = client.post('/dashboard/login', data={
        'csrf': field(page, 'csrf'), 'secret': os.environ['APP_SHARED_SECRET'],
    }, follow_redirects=False)
    assert result.status_code == 303


def approve(client, response):
    if response.status_code != 200 or 'approval_url' not in response.json():
        return response
    browser_login(client)
    url = response.json()['approval_url']
    page = client.get(url)
    if page.status_code != 200:
        return page
    return client.post(url, data={'csrf': field(page, 'csrf'), 'nonce': field(page, 'nonce'),
                                 'decision': 'approve'}, headers={'Accept': 'application/json'})


def approved_post(client, path, **kwargs):
    return approve(client, client.post(path, **kwargs))
