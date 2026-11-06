package page.zepto.finto.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import page.zepto.finto.core.data.ApplicationScope
import page.zepto.finto.core.data.IoDispatcher
import page.zepto.finto.core.data.repository.AdminRepository
import page.zepto.finto.core.data.repository.AskRepository
import page.zepto.finto.core.data.repository.DataStoreSettingsRepository
import page.zepto.finto.core.data.repository.InvestmentsRepository
import page.zepto.finto.core.data.repository.NetworkAdminRepository
import page.zepto.finto.core.data.repository.NetworkAskRepository
import page.zepto.finto.core.data.repository.NetworkInvestmentsRepository
import page.zepto.finto.core.data.repository.NetworkReviewRepository
import page.zepto.finto.core.data.repository.ReviewRepository
import page.zepto.finto.core.data.repository.DefaultSessionRepository
import page.zepto.finto.core.data.repository.ImportRepository
import page.zepto.finto.core.data.repository.LedgerRepository
import page.zepto.finto.core.data.repository.NetworkImportRepository
import page.zepto.finto.core.data.repository.NetworkLedgerRepository
import page.zepto.finto.core.data.repository.SessionRepository
import page.zepto.finto.core.data.repository.SettingsRepository
import page.zepto.finto.core.data.repository.settingsDataStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataBindings {
    @Binds
    abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun session(impl: DefaultSessionRepository): SessionRepository

    @Binds
    abstract fun ledger(impl: NetworkLedgerRepository): LedgerRepository

    @Binds
    abstract fun imports(impl: NetworkImportRepository): ImportRepository

    @Binds
    abstract fun review(impl: NetworkReviewRepository): ReviewRepository

    @Binds
    abstract fun investments(impl: NetworkInvestmentsRepository): InvestmentsRepository

    @Binds
    abstract fun ask(impl: NetworkAskRepository): AskRepository

    @Binds
    abstract fun admin(impl: NetworkAdminRepository): AdminRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal object DataProviders {
    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
