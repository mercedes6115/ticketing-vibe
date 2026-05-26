import { BrowserRouter, Routes, Route, Navigate, Outlet, useLocation } from 'react-router-dom';
import { useUserStore } from './stores/userStore';
import Layout from './components/Layout';
import HomePage from './pages/HomePage';
import EventDetailPage from './pages/EventDetailPage';
import QueuePage from './pages/QueuePage';
import SeatSelectPage from './pages/SeatSelectPage';
import PaymentPage from './pages/PaymentPage';
import BookingCompletePage from './pages/BookingCompletePage';
import MyBookingsPage from './pages/MyBookingsPage';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import AdminEventPage from './pages/AdminEventPage';
import AdminBookingPage from './pages/AdminBookingPage';
import AdminUserPage from './pages/AdminUserPage';

function RequireAuth() {
  const { user } = useUserStore();
  const location = useLocation();
  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  return <Outlet />;
}

function RequireAdmin() {
  const { user } = useUserStore();
  const location = useLocation();
  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  if (user.role !== 'ADMIN') {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 인증 불필요 */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/signup" element={<SignupPage />} />

        {/* 레이아웃 포함 라우트 */}
        <Route path="/" element={<Layout />}>
          {/* 공개 라우트 */}
          <Route index element={<HomePage />} />
          <Route path="events/:id" element={<EventDetailPage />} />

          {/* 로그인 필요 */}
          <Route element={<RequireAuth />}>
            <Route path="events/:id/queue" element={<QueuePage />} />
            <Route path="events/:id/seats" element={<SeatSelectPage />} />
            <Route path="events/:id/payment" element={<PaymentPage />} />
            <Route path="bookings/:id/complete" element={<BookingCompletePage />} />
            <Route path="my/bookings" element={<MyBookingsPage />} />
          </Route>

          {/* 어드민 전용 */}
          <Route element={<RequireAdmin />}>
            <Route path="admin/events" element={<AdminEventPage />} />
            <Route path="admin/bookings" element={<AdminBookingPage />} />
            <Route path="admin/users" element={<AdminUserPage />} />
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

export default App;
