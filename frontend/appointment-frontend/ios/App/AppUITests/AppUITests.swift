import XCTest

final class AppUITests: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false
        app.launch()
    }

    override func tearDownWithError() throws {
        XCUIDevice.shared.orientation = .portrait
    }

    func testNativeWebViewNavigationFocusKeyboardAndSafeArea() throws {
        let appointmentTab = app.links["예약 관리"]
        XCTAssertTrue(
            appointmentTab.waitForExistence(timeout: 30),
            "bottom tab must be exposed through the native accessibility tree"
        )
        XCTAssertGreaterThan(appointmentTab.frame.width, 0)
        XCTAssertGreaterThan(appointmentTab.frame.height, 0)

        let bottomNavigation = app.otherElements["모바일 하단 내비게이션 영역"]
        XCTAssertTrue(
            bottomNavigation.waitForExistence(timeout: 10),
            "bottom navigation container must expose its native accessibility frame"
        )
        XCTAssertGreaterThanOrEqual(bottomNavigation.frame.height, 44)

        appointmentTab.tap()

        let appointmentTitle = app.staticTexts["예약 목록"]
        if !appointmentTitle.waitForExistence(timeout: 5) && appointmentTab.exists {
            appointmentTab.tap()
        }
        XCTAssertTrue(
            appointmentTitle.waitForExistence(timeout: 15),
            "native tap must navigate to the appointment route"
        )

        let dateField = app.textFields["시작일"]
        XCTAssertTrue(
            dateField.waitForExistence(timeout: 10),
            "the date input must be accessible for focus evidence"
        )
        dateField.tap()
        XCTAssertTrue(
            app.keyboards.firstMatch.waitForExistence(timeout: 5),
            "focusing the date input must expose the simulator keyboard"
        )
        XCTAssertTrue(dateField.hasFocus)

        assertContentFitsWindow()

        XCUIDevice.shared.orientation = .landscapeLeft
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5))
        assertContentFitsWindow()
    }

    private func assertContentFitsWindow() {
        let window = app.windows.firstMatch
        XCTAssertTrue(window.exists)
        let keyboardVisible = app.keyboards.firstMatch.exists
        if !keyboardVisible {
            XCTAssertLessThanOrEqual(
                app.staticTexts["예약 목록"].frame.maxY,
                window.frame.maxY + 1
            )
        }
        let dateField = app.textFields["시작일"]
        if dateField.exists {
            XCTAssertGreaterThanOrEqual(dateField.frame.minY, window.frame.minY - 1)
            XCTAssertLessThanOrEqual(dateField.frame.maxY, window.frame.maxY + 1)
        }
        if app.links["예약 관리"].exists {
            XCTAssertLessThanOrEqual(
                app.links["예약 관리"].frame.maxY,
                window.frame.maxY + 1
            )
        }
    }
}
