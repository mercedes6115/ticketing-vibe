import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { authApi } from '../api/auth';

export default function Layout() {
  const { user, logout } = useUserStore();
  const navigate = useNavigate();

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch {
      // 서버 오류여도 클라이언트 로그아웃은 진행
    }
    logout();
    navigate('/', { replace: true });
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* 헤더 */}
      <header className="bg-white shadow-sm">
        <div className="max-w-6xl mx-auto px-4">
          <div className="flex justify-between items-center h-16">
            <Link to="/" className="text-xl font-bold text-blue-600">
              🎫 Ticketing
            </Link>
            <nav className="flex items-center gap-4">
              <Link to="/" className="text-gray-600 hover:text-gray-900">
                이벤트
              </Link>
              {user && (
                <Link to="/my/bookings" className="text-gray-600 hover:text-gray-900">
                  내 예매
                </Link>
              )}
              {user?.role === 'ADMIN' && (
                <>
                  <Link
                    to="/admin/events"
                    className="text-gray-600 hover:text-gray-900"
                  >
                    이벤트 관리
                  </Link>
                  <Link
                    to="/admin/bookings"
                    className="text-gray-600 hover:text-gray-900"
                  >
                    예매 관리
                  </Link>
                  <Link
                    to="/admin/users"
                    className="text-gray-600 hover:text-gray-900"
                  >
                    사용자 관리
                  </Link>
                </>
              )}
              {user ? (
                <div className="flex items-center gap-3">
                  <span className="text-sm text-gray-500">{user.nickname}님</span>
                  <button
                    onClick={handleLogout}
                    className="px-3 py-1 text-sm border border-gray-300 text-gray-600 rounded hover:bg-gray-50 transition"
                  >
                    로그아웃
                  </button>
                </div>
              ) : (
                <div className="flex items-center gap-2">
                  <Link
                    to="/login"
                    className="px-3 py-1 text-sm text-gray-600 hover:text-gray-900"
                  >
                    로그인
                  </Link>
                  <Link
                    to="/signup"
                    className="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 transition"
                  >
                    회원가입
                  </Link>
                </div>
              )}
            </nav>
          </div>
        </div>
      </header>

      {/* 메인 컨텐츠 */}
      <main className="pb-20">
        <Outlet />
      </main>

      {/* 푸터 */}
      <footer className="bg-gray-800 text-gray-400 py-8">
        <div className="max-w-6xl mx-auto px-4 text-center text-sm">
          <p>© 2024 Ticketing System. 포트폴리오 프로젝트입니다.</p>
          <p className="mt-2">
            Redis 분산락 · WebSocket 실시간 · SSE 대기열
          </p>
        </div>
      </footer>
    </div>
  );
}
