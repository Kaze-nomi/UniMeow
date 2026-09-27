import base64
import re
import time
import uuid
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit

import pytest
import requests

from synthetic_oauth import start_provider

API = 'http://localhost:8082'
FRONTEND = 'http://localhost:5173'
JOBS = {'api-gateway', 'user-service', 'post-service', 'feed-service', 'media-service', 'notification-service'}
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=')


def require(condition, message):
    if not condition:
        pytest.fail(message, pytrace=False)


def http(session, method, url, **kwargs):
    try:
        return session.request(method, url, timeout=20, allow_redirects=False, **kwargs)
    except requests.RequestException:
        pytest.fail('HTTP request failed; request headers and cookies are intentionally omitted', pytrace=False)


def document(response):
    require(response.status_code == 200, 'Expected a successful HTTP response')
    try:
        return response.json()
    except ValueError:
        pytest.fail('Expected JSON response', pytrace=False)


def graphql(session, query, variables=None):
    result = document(http(session, 'POST', API + '/graphql', json={'query': query, 'variables': variables or {}}))
    if result.get('errors'):
        operation = re.search(r'\{\s*(\w+)', query).group(1)
        codes = [error.get('extensions', {}).get('code', error.get('extensions', {}).get('classification', 'UNKNOWN')) for error in result['errors']]
        codes = [code if isinstance(code, str) and re.fullmatch(r'[A-Za-z_]+', code) else 'UNKNOWN' for code in codes]
        pytest.fail('GraphQL ' + operation + ' failed: ' + ', '.join(codes))
    return result['data']


def eventually(check, description, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(1)
    pytest.fail('Timed out: ' + description, pytrace=False)


def login(email, session=None):
    session = session if session is not None else requests.Session()
    response = http(session, 'GET', API + '/oauth2/authorization/google')
    require(response.status_code == 302, 'OAuth authorization redirect was not returned')
    authorize = urlsplit(response.headers.get('Location', ''))
    require((authorize.scheme, authorize.netloc, authorize.path) == ('http', 'localhost:18080', '/authorize'),
            'Refusing login: Gateway must use the synthetic E2E provider override')
    query = parse_qs(authorize.query)
    query['login_hint'] = [email]
    location = urlunsplit(authorize._replace(query=urlencode(query, doseq=True)))
    response = http(session, 'GET', location)
    require(response.status_code == 302, 'Synthetic provider did not issue an authorization code')
    callback = response.headers.get('Location', '')
    require(callback.startswith(API + '/login/oauth2/code/google?'), 'Unexpected OAuth callback destination')
    response = http(session, 'GET', callback)
    require(response.status_code == 302 and response.headers.get('Location') == FRONTEND + '/', 'OAuth callback failed')
    require(bool(session.cookies.get('ACCESS_TOKEN')) and bool(session.cookies.get('REFRESH_TOKEN')), 'Gateway did not issue authentication cookies')
    return session


@pytest.fixture(scope='session')
def synthetic_provider():
    server, thread = start_provider()
    yield
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)


@pytest.fixture
def accounts(synthetic_provider):
    created = []

    def create():
        email = 'e2e-' + uuid.uuid4().hex + '@example.invalid'
        account = {'email': email, 'session': requests.Session(), 'id': None}
        created.append(account)
        session = login(email, account['session'])
        user = graphql(session, 'query{me{id emailGoogle}}')['me']
        require(user['emailGoogle'] == email, 'OAuth user identity did not match the synthetic provider')
        account['id'] = user['id']
        return account

    yield create
    failures = 0
    for account in reversed(created):
        try:
            if not account['session'].cookies.get('ACCESS_TOKEN'):
                account['session'].close()
                account['session'] = login(account['email'])
            if account['id'] is None:
                account['id'] = graphql(account['session'], 'query{me{id}}')['me']['id']
            result = graphql(account['session'], 'mutation{deleteAccount{success}}')
            require(result['deleteAccount']['success'], 'Synthetic account cleanup was refused')
            with requests.Session() as guest:
                eventually(lambda: graphql(guest, 'query($id:ID!){getUserPosts(userId:$id,size:1){total}}',
                                           {'id': account['id']})['getUserPosts']['total'] == 0,
                           'account deletion removes all synthetic posts')
        except (Exception, pytest.fail.Exception):
            failures += 1
        finally:
            account['session'].close()
    require(failures == 0, 'API cleanup failed for one or more synthetic accounts')


def test_frontend_runtime_and_eureka():
    with requests.Session() as guest:
        page = http(guest, 'GET', FRONTEND + '/')
        require(page.status_code == 200 and b'<html' in page.content.lower(), 'Frontend HTML is unavailable')
        response = http(guest, 'GET', FRONTEND + '/runtime-config.json')
        require(document(response) == {'apiBase': API, 'minioPublicUrl': 'http://localhost:9000'}, 'Frontend runtime URLs differ from dev Compose')
        require('no-store' in response.headers.get('Cache-Control', ''), 'Runtime configuration may be cached')

        def registry_ready():
            registry = document(http(guest, 'GET', 'http://localhost:8761/eureka/apps', headers={'Accept': 'application/json'}))
            applications = registry['applications'].get('application') or []
            if isinstance(applications, dict):
                applications = [applications]
            registered = set()
            for app in applications:
                instances = app.get('instance') or []
                if isinstance(instances, dict):
                    instances = [instances]
                if any(instance.get('status') == 'UP' for instance in instances):
                    registered.add(app['name'].lower())
            return JOBS <= registered

        eventually(registry_ready, 'all six application clients register UP in Eureka')

        def guest_ready():
            data = document(http(guest, 'POST', API + '/graphql',
                                 json={'query': 'query{listUniversities{id} trendingFeed(size:1){posts{id}}}'}))
            return not data.get('errors') and isinstance((data.get('data') or {}).get('listUniversities'), list)

        eventually(guest_ready, 'Gateway discovery routes reach UserService and FeedService')


def test_monitoring():
    with requests.Session() as guest:
        def grafana_ready():
            try:
                response = guest.get('http://localhost:3000/api/health', timeout=5)
                return response.status_code == 200 and response.json().get('database') == 'ok'
            except (requests.RequestException, ValueError):
                return False

        eventually(grafana_ready, 'Grafana database is ready')

        def targets_up():
            result = document(http(guest, 'GET', 'http://localhost:9700/api/v1/targets'))
            targets = result['data']['activeTargets']
            return all(any(target['labels'].get('job') == job for target in targets)
                       and all(target['health'] == 'up' for target in targets if target['labels'].get('job') == job)
                       for job in JOBS)

        eventually(targets_up, 'all six Prometheus application jobs are up')


def test_oauth_refresh_and_logout(accounts):
    account = accounts()
    session = account['session']
    previous = session.cookies.get('REFRESH_TOKEN')
    require(http(session, 'POST', API + '/api/auth/refresh').status_code == 200, 'Refresh failed')
    require(bool(session.cookies.get('REFRESH_TOKEN')) and session.cookies.get('REFRESH_TOKEN') != previous, 'Refresh token did not rotate')
    require(graphql(session, 'query{me{id}}')['me']['id'] == account['id'], 'Refresh changed the user identity')
    with requests.Session() as replay:
        response = http(replay, 'POST', API + '/api/auth/refresh', headers={'Cookie': 'REFRESH_TOKEN=' + previous})
        require(response.status_code == 401, 'The previous refresh token was accepted again')
    require(http(session, 'POST', API + '/api/auth/logout').status_code == 200, 'Logout failed')
    require(not session.cookies.get('ACCESS_TOKEN') and not session.cookies.get('REFRESH_TOKEN'), 'Logout did not remove authentication cookies')
    require(http(session, 'POST', API + '/api/auth/refresh').status_code == 401, 'Unauthenticated refresh succeeded')


def test_posts_feeds_notifications_and_media(accounts):
    author, follower = accounts(), accounts()
    a, b = author['session'], follower['session']
    for account in [author, follower]:
        username = 'e2e_' + uuid.uuid4().hex[:16]
        profile = graphql(account['session'], 'mutation($input:UpdateProfileInput!){updateProfile(input:$input){username name}}',
                          {'input': {'username': username, 'name': 'E2E user'}})['updateProfile']
        require(profile['username'] == username, 'Profile update did not persist')
    require(graphql(b, 'mutation($id:ID!){subscribe(targetUserId:$id){success}}', {'id': author['id']})['subscribe']['success'], 'Subscribe failed')

    def notified(kind, entity=None):
        values = graphql(a, 'query{getNotifications(size:50){notifications{type actorId entityId}}}')['getNotifications']['notifications']
        return any(n['type'] == kind and n['actorId'] == follower['id'] and (entity is None or n['entityId'] == entity) for n in values)

    eventually(lambda: notified('FOLLOW'), 'follow event creates a notification')
    uploaded = document(http(a, 'POST', API + '/api/upload?bucket=post-media', files={'file': ('e2e.png', PNG, 'image/png')}))['url']
    require(uploaded.startswith('http://localhost:9000/post-media/'), 'Upload returned an unexpected media location')
    require(http(a, 'GET', uploaded).content == PNG, 'MinIO public read differs from uploaded bytes')
    create = 'mutation($input:CreatePostInput!,$key:String!){createPost(input:$input,clientRequestId:$key){id}}'
    first_input = {'input': {'content': 'Synthetic E2E media post', 'mediaUrls': [uploaded]}, 'key': str(uuid.uuid4())}
    first = graphql(a, create, first_input)['createPost']['id']
    require(graphql(a, create, first_input)['createPost']['id'] == first, 'Post idempotency key created a second post')
    second = graphql(a, create, {'input': {'content': 'Synthetic E2E newer post'}, 'key': str(uuid.uuid4())})['createPost']['id']

    def feed(name):
        return [post['id'] for post in graphql(b, 'query{' + name + '(size:100){posts{id}}}')[name]['posts']]

    eventually(lambda: {first, second} <= set(feed('followingFeed')), 'post events populate the follower Redis feed')
    require(graphql(b, 'mutation($id:ID!){likePost(postId:$id){success}}', {'id': first})['likePost']['success'], 'Like failed')

    def ranked(older_first):
        posts = feed('trendingFeed')
        return first in posts and second in posts and ((posts.index(first) < posts.index(second)) == older_first)

    eventually(lambda: ranked(True), 'like event raises the older post in the Redis trending feed')
    eventually(lambda: notified('LIKE_POST', first), 'post like event creates a notification')
    require(graphql(b, 'mutation($id:ID!){unlikePost(postId:$id){success}}', {'id': first})['unlikePost']['success'], 'Unlike failed')
    eventually(lambda: ranked(False), 'unlike event restores chronological trending order')
    comment = graphql(b, 'mutation($id:ID!,$key:String!){addComment(postId:$id,content:"Synthetic E2E comment",clientRequestId:$key){id}}',
                      {'id': first, 'key': str(uuid.uuid4())})['addComment']['id']
    eventually(lambda: notified('COMMENT_ON_POST', comment), 'comment event creates a notification')
    require(graphql(b, 'mutation($id:ID!){deleteComment(commentId:$id){success}}', {'id': comment})['deleteComment']['success'], 'Comment deletion failed')
    require(graphql(a, 'mutation{markAllNotificationsRead}')['markAllNotificationsRead'], 'Mark notifications read failed')
    require(graphql(a, 'query{getUnreadNotificationCount}')['getUnreadNotificationCount'] == 0, 'Notifications remained unread')
    require(graphql(a, 'mutation($id:ID!){deletePost(postId:$id){success}}', {'id': first})['deletePost']['success'], 'Post deletion failed')
    eventually(lambda: first not in feed('followingFeed') and first not in feed('trendingFeed'), 'deleted post disappears from feeds')
    require(graphql(b, 'mutation($id:ID!){unsubscribe(targetUserId:$id){success}}', {'id': author['id']})['unsubscribe']['success'], 'Unsubscribe failed')
    eventually(lambda: second not in feed('followingFeed'), 'unfollow event removes the remaining post from the follower feed')
